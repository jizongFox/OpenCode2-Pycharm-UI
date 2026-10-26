package ai.opencode.ide.jetbrains

import ai.opencode.ide.jetbrains.api.OpenCodeApiClient
import ai.opencode.ide.jetbrains.api.ConnectionProbe
import ai.opencode.ide.jetbrains.api.OpenCodeClient
import ai.opencode.ide.jetbrains.api.OpenCodeEventStream
import ai.opencode.ide.jetbrains.diff.DiffViewerService
import ai.opencode.ide.jetbrains.review.ReviewCoordinator
import ai.opencode.ide.jetbrains.runtime.OpenCodeCli
import ai.opencode.ide.jetbrains.runtime.ConnectionLifecycle
import ai.opencode.ide.jetbrains.session.SessionManager
import ai.opencode.ide.jetbrains.terminal.TerminalHost
import ai.opencode.ide.jetbrains.terminal.OpenCodeTerminalFileEditor
import ai.opencode.ide.jetbrains.terminal.OpenCodeTerminalVirtualFile
import ai.opencode.ide.jetbrains.terminal.PromptDelivery
import ai.opencode.ide.jetbrains.ui.OpenCodeConnectDialog
import ai.opencode.ide.jetbrains.util.PathUtil
import ai.opencode.ide.jetbrains.util.PortFinder
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.util.concurrency.AppExecutorUtil
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.nio.file.Paths
import java.security.SecureRandom
import java.util.Base64

/**
 * Project-level facade used by IDE actions.
 *
 * This class owns connection lifecycle only. Terminal rendering belongs to
 * [TerminalHost], turn review belongs to [ReviewCoordinator], and CLI details
 * belong to [OpenCodeCli].
 */
@Service(Service.Level.PROJECT)
class OpenCodeService(private val project: Project) : Disposable {

    private enum class ConnectionMode { TERMINAL, REMOTE }

    private data class ServerConnection(
        val hostname: String,
        val port: Int,
        val username: String?,
        val password: String?,
        val mode: ConnectionMode,
        val workingDirectory: String?,
        val generation: Long
    ) {
        val apiHost: String get() = if (hostname == "0.0.0.0") DEFAULT_HOST else hostname
    }

    private data class ConnectionReset(val generation: Long, val shutdown: CompletableFuture<Void>)

    private val logger = Logger.getInstance(OpenCodeService::class.java)

    @Volatile
    private var workspaceRoot: String? = normalizeWorkspace(project.basePath)

    private val terminalHost = TerminalHost(project, ::resolveWorkspacePath)
    private val cli = OpenCodeCli()
    private val connectionLifecycle = ConnectionLifecycle { project.isDisposed }
    @Volatile private var localServerProcess: Process? = null
    @Volatile private var localServerStarting = false

    @Volatile
    private var connection: ServerConnection? = null

    @Volatile
    private var apiClient: OpenCodeClient? = null

    @Volatile
    private var eventStream: OpenCodeEventStream? = null
    private var connectionManagerTask: ScheduledFuture<*>? = null
    private val isConnected = AtomicBoolean(false)
    private val isConnecting = AtomicBoolean(false)
    private val isConnectionDialogOpen = AtomicBoolean(false)
    private var wasEverConnected = false
    private var reconnectFailures = 0
    private var reconnectDialogShown = false

    private var testSessionManager: SessionManager? = null
    private var testDiffViewerService: DiffViewerService? = null

    val sessionManager: SessionManager
        get() = testSessionManager ?: project.service()

    private val diffViewerService: DiffViewerService
        get() = testDiffViewerService ?: project.service()

    internal var invokeLater: (Runnable) -> Unit = {
        ApplicationManager.getApplication().invokeLater(it)
    }
    internal var pasteToTerminal: (String) -> Boolean = terminalHost::appendPrompt
    internal var focusAfterPaste: () -> Unit = terminalHost::focus
    internal var hasRunningTerminal: () -> Boolean = terminalHost::isOpen

    private val promptDelivery = PromptDelivery(
        paste = { text ->
            try {
                pasteToTerminal(text)
            } catch (e: Exception) {
                logger.debug("[Paste] Terminal unavailable: ${e.message}")
                false
            }
        },
        onDelivered = { if (!project.isDisposed) focusAfterPaste() },
        onFailure = { text ->
            logger.warn("[Paste] All retries exhausted for: $text")
            showPasteFailure()
        },
        schedule = { delay, action ->
            AppExecutorUtil.getAppScheduledExecutorService().schedule(
                Runnable { action() }, delay, TimeUnit.MILLISECONDS
            )
        },
        postToUi = { action -> invokeLater(Runnable { action() }) }
    )

    private val reviewCoordinator = ReviewCoordinator(
        project = project,
        sessionManager = { sessionManager },
        diffViewerService = { diffViewerService },
        client = { apiClient },
        workspaceRoot = { workspaceRoot },
        invokeLater = { invokeLater(it) }
    )

    internal fun setTestDeps(
        sessionManager: SessionManager,
        diffViewerService: DiffViewerService,
        client: OpenCodeClient
    ) {
        testSessionManager = sessionManager
        testDiffViewerService = diffViewerService
        apiClient = client
    }

    internal fun connectEventStreamForTest() {
        connectToSse()
    }

    internal fun isConnectedForTest(): Boolean = isConnected.get()

    internal fun configureWorkspaceForTest(path: String?) {
        configureWorkspace(path)
    }

    // Public facade used by IDE actions.

    fun focusOrCreateTerminal(interactive: Boolean = false) {
        whenTerminalCreationIsAvailable {
            focusOrCreateTerminalNow(interactive)
        }
    }

    private fun focusOrCreateTerminalNow(interactive: Boolean) {
        val current = connection
        if (localServerStarting) return
        if (hasRunningTerminal()) {
            if (interactive) terminalHost.focus()
            return
        }
        if (current == null) {
            showConnectionDialog()
            return
        }

        AppExecutorUtil.getAppExecutorService().submit {
            val running = newApiClient(current).probe() == ConnectionProbe.Ready
            invokeForConnection(current.generation) {
                if (!running) isConnected.set(false)
                when {
                    interactive -> showReconnectOrNewDialog(current, running)
                    running -> restoreUi(current)
                    else -> showReconnectOrNewDialog(current, false)
                }
            }
        }
    }

    fun toReferencePath(path: String): String? {
        return PathUtil.contextReferencePath(workspaceRoot ?: project.basePath, path)
    }

    internal fun createTerminalEditor(file: OpenCodeTerminalVirtualFile): OpenCodeTerminalFileEditor =
        OpenCodeTerminalFileEditor(file, terminalHost)

    fun focusOrCreateTerminalAndPaste(text: String) {
        if (text.isBlank() || project.isDisposed) return
        promptDelivery.enqueue(text)
        whenTerminalCreationIsAvailable(::resumePasteDelivery)
    }

    private fun resumePasteDelivery() {
        if (hasRunningTerminal()) {
            invokeForConnection(connectionLifecycle.current()) { terminalHost.show() }
            promptDelivery.resume()
        } else {
            promptDelivery.pause(clearPending = false)
            if (connection == null) showConnectionDialog()
            else focusOrCreateTerminalNow(interactive = false)
        }
    }

    /** TerminalView cannot create a usable widget while the IDE is indexing. */
    private fun whenTerminalCreationIsAvailable(action: () -> Unit) {
        if (hasRunningTerminal() || !DumbService.isDumb(project)) {
            action()
            return
        }

        val token = connectionLifecycle.current()
        DumbService.getInstance(project).runWhenSmart {
            if (connectionLifecycle.isCurrent(token)) action()
        }
    }

    // Connection lifecycle.

    private fun invokeForConnection(token: Long, action: () -> Unit) {
        invokeLater(Runnable {
            if (connectionLifecycle.isCurrent(token)) action()
        })
    }

    private fun showConnectionDialog() {
        if (project.isDisposed || !isConnectionDialogOpen.compareAndSet(false, true)) return
        val token = connectionLifecycle.current()
        AppExecutorUtil.getAppExecutorService().submit {
            val suggestedPort = try {
                PortFinder.findAvailablePort()
            } catch (e: Exception) {
                logger.warn("[OpenCode] Failed to find an available port", e)
                DEFAULT_PORT
            }
            invokeForConnection(token) {
                try {
                    val choice = OpenCodeConnectDialog.show(project, suggestedPort)
                    if (!connectionLifecycle.isCurrent(token)) return@invokeForConnection
                    if (choice != null) processConnectionChoice(choice)
                    else promptDelivery.pause(clearPending = true)
                } finally {
                    isConnectionDialogOpen.set(false)
                }
            }
        }
    }

    private fun processConnectionChoice(choice: OpenCodeConnectDialog.ConnectionInfo) {
        val reset = disconnectAndReset(clearPendingPastes = false)
        val hostname = choice.hostname.trim().ifBlank { DEFAULT_HOST }
        reset.shutdown.whenComplete { _, _ ->
            AppExecutorUtil.getAppExecutorService().submit {
                if (!connectionLifecycle.isCurrent(reset.generation)) return@submit
                val config = ServerConnection(
                    hostname, choice.port, "opencode", choice.password,
                    if (hostname in LOCAL_HOSTS) ConnectionMode.TERMINAL else ConnectionMode.REMOTE,
                    choice.customBasePath ?: project.basePath, reset.generation
                )
                val probe = newApiClient(config).probe()
                val canStartLocal = config.mode == ConnectionMode.TERMINAL &&
                    probe is ConnectionProbe.Unavailable && PortFinder.isPortAvailable(config.port)
                invokeForConnection(config.generation) {
                    when {
                        probe == ConnectionProbe.Ready -> connectToExistingServer(config)
                        canStartLocal -> createLocalTerminal(config)
                        else -> {
                            showConnectionFailure(probe)
                            if (connectionLifecycle.isCurrent(config.generation)) showConnectionDialog()
                        }
                    }
                }
            }
        }
    }

    private fun showConnectionFailure(probe: ConnectionProbe) {
        val message = when (probe) {
            ConnectionProbe.Ready -> return
            ConnectionProbe.AuthenticationRequired -> "OpenCode V2 rejected this connection. Check the server password."
            is ConnectionProbe.Unsupported -> probe.reason
            is ConnectionProbe.Unavailable -> "Cannot connect to OpenCode. ${probe.reason}"
        }
        Messages.showWarningDialog(project, message, "Connection Failed")
    }

    private fun newApiClient(config: ServerConnection): OpenCodeApiClient =
        OpenCodeApiClient(config.apiHost, config.port, config.username, config.password)

    private fun connectToExistingServer(config: ServerConnection) {
        if (!connectionLifecycle.update(config.generation) {
                configureWorkspace(config.workingDirectory)
                connection = config
                apiClient = newApiClient(config)
                startConnectionManager(config.generation)
            }) return
        AppExecutorUtil.getAppExecutorService().submit {
            val binary = cli.detect()
            invokeForConnection(config.generation) {
                if (!hasRunningTerminal()) {
                    if (binary != null) createTerminalUi(config, binary, continueSession = true)
                    else {
                        promptDelivery.pause(clearPending = true)
                        showCliNotFoundError()
                    }
                }
            }
        }
    }

    private fun createLocalTerminal(requested: ServerConnection) {
        val config = requested.copy(password = requested.password ?: randomPassword())
        if (!connectionLifecycle.update(config.generation) {
                configureWorkspace(config.workingDirectory)
                connection = config
                localServerStarting = true
            }) return
        AppExecutorUtil.getAppExecutorService().submit {
            try {
                val binary = cli.detect()
                if (binary == null) {
                    invokeForConnection(config.generation) {
                        localServerStarting = false
                        promptDelivery.pause(clearPending = true)
                        showCliNotFoundError()
                    }
                    return@submit
                }
                if (!connectionLifecycle.isCurrent(config.generation)) return@submit
                val server = cli.startServer(
                    binary, config.hostname, config.port, config.workingDirectory,
                    checkNotNull(config.password)
                )
                // The worker owns this process until publication succeeds. Reset owns it afterwards.
                if (!connectionLifecycle.update(config.generation) { localServerProcess = server }) {
                    terminateServer(server)
                    return@submit
                }
                if (!PortFinder.waitForPort(
                        config.port, config.apiHost, timeoutMs = SERVER_START_TIMEOUT_MS,
                        username = config.username, password = config.password
                    )) error("OpenCode V2 server did not become ready")
                invokeForConnection(config.generation) {
                    try {
                        connectionLifecycle.update(config.generation) {
                            apiClient = newApiClient(config)
                            createTerminalUi(config, binary, continueSession = true)
                            startConnectionManager(config.generation)
                            localServerStarting = false
                        }
                    } catch (e: Exception) {
                        disconnectAndReset()
                        Messages.showErrorDialog(project, e.message ?: "Cannot open OpenCode terminal", "OpenCode")
                    }
                }
            } catch (e: Exception) {
                logger.warn("[OpenCode] Failed to start V2 server", e)
                invokeForConnection(config.generation) {
                    disconnectAndReset()
                    Messages.showErrorDialog(project, e.message ?: "Cannot start OpenCode V2", "OpenCode")
                }
            }
        }
    }

    private fun createTerminalUi(
        config: ServerConnection,
        command: String = cli.command(),
        continueSession: Boolean
    ) {
        connectionLifecycle.update(config.generation) {
            val launchCommand = cli.buildCommand(
                command, config.hostname, config.port, config.password, continueSession
            )
            terminalHost.create(
                config.workingDirectory, "$OPEN_CODE_TAB_PREFIX(${config.port})", launchCommand
            )
            promptDelivery.resume()
        }
    }

    private fun startConnectionManager(token: Long) {
        if (connectionManagerTask?.isDone == false) return
        connectionManagerTask = AppExecutorUtil.getAppScheduledExecutorService().scheduleWithFixedDelay(
            { tryConnect(token) }, 0, CONNECTION_RETRY_INTERVAL_MS, TimeUnit.MILLISECONDS
        )
    }

    private fun tryConnect(token: Long) {
        val client = apiClient ?: return
        var shouldConnect = false
        if (!connectionLifecycle.update(token) {
                shouldConnect = !isConnected.get() && isConnecting.compareAndSet(false, true)
            } || !shouldConnect) return
        try {
            val healthy = client.checkHealth()
            connectionLifecycle.update(token) {
                if (healthy) {
                    reconnectFailures = 0
                    reconnectDialogShown = false
                    wasEverConnected = true
                    connectToSse(token)
                } else handleConnectionFailure()
            }
        } catch (_: Exception) {
            connectionLifecycle.update(token) { handleConnectionFailure() }
        } finally {
            connectionLifecycle.update(token) { isConnecting.set(false) }
        }
    }

    private fun connectToSse(token: Long = connectionLifecycle.current()) {
        val client = apiClient ?: return
        val directory = workspaceRoot ?: return
        if (eventStream != null || !connectionLifecycle.isCurrent(token)) return
        lateinit var stream: OpenCodeEventStream
        val reviewToken = reviewCoordinator.currentGeneration()
        stream = client.createEventStream(
            directory = directory,
            onEvent = { event ->
                connectionLifecycle.update(token) {
                    if (eventStream === stream) reviewCoordinator.handle(event, reviewToken)
                }
            },
            onError = {
                connectionLifecycle.update(token) {
                    if (eventStream === stream) isConnected.set(false)
                }
            },
            onConnected = {
                connectionLifecycle.update(token) {
                    if (eventStream === stream) {
                        isConnected.set(true)
                        AppExecutorUtil.getAppExecutorService().submit {
                            try {
                                val active = client.getActiveSessionIds()
                                connectionLifecycle.update(token) {
                                    if (eventStream === stream) {
                                        reviewCoordinator.reconcileActiveSessions(active, reviewToken)
                                    }
                                }
                            } catch (e: Exception) {
                                logger.warn("[OpenCode] Could not reconcile active sessions", e)
                            }
                        }
                    }
                }
            },
            onDisconnected = {
                connectionLifecycle.update(token) {
                    if (eventStream === stream) {
                        eventStream = null
                        isConnected.set(false)
                    }
                }
            }
        )
        if (!connectionLifecycle.update(token) {
                if (eventStream == null) {
                    eventStream = stream
                    stream.connect()
                } else stream.disconnect()
            }) stream.disconnect()
    }

    private fun handleConnectionFailure() {
        if (connection == null || !wasEverConnected) return
        reconnectFailures++
        if (reconnectFailures >= RECONNECT_FAILURE_THRESHOLD && !reconnectDialogShown) {
            reconnectDialogShown = true
            showRemoteReconnectDialog()
        }
    }

    private fun showRemoteReconnectDialog() {
        val token = connectionLifecycle.current()
        invokeForConnection(token) {
            if (isConnected.get()) return@invokeForConnection
            val choice = Messages.showYesNoCancelDialog(
                project,
                "Connection lost. Reconnect?",
                "OpenCode",
                "Reconnect",
                "New",
                "Cancel",
                Messages.getWarningIcon()
            )
            if (connectionLifecycle.isCurrent(token) && choice == Messages.NO) {
                disconnectAndReset()
                showConnectionDialog()
            }
        }
    }

    private fun showReconnectOrNewDialog(config: ServerConnection, running: Boolean) {
        val message = if (running) "Session active. Restore UI?" else "Server dead. Restart?"
        invokeForConnection(config.generation) {
            val choice = Messages.showYesNoCancelDialog(
                project,
                message,
                "OpenCode",
                if (running) "Restore" else "Restart",
                "New",
                "Cancel",
                Messages.getQuestionIcon()
            )
            if (!connectionLifecycle.isCurrent(config.generation)) return@invokeForConnection
            when (choice) {
                Messages.YES -> if (running) restoreUi(config) else restartServer(config)
                Messages.NO -> {
                    disconnectAndReset()
                    showConnectionDialog()
                }
            }
        }
    }

    private fun restoreUi(config: ServerConnection) {
        ensureTerminalUi(config)
    }

    private fun ensureTerminalUi(config: ServerConnection) {
        if (hasRunningTerminal()) {
            terminalHost.focus()
            return
        }
        try {
            createTerminalUi(config, continueSession = true)
        } catch (e: Exception) {
            logger.warn("[OpenCode] Failed to create terminal UI", e)
            invokeForConnection(config.generation) {
                Messages.showErrorDialog(
                    project,
                    "Failed to create terminal: ${e.message}",
                    "OpenCode"
                )
            }
        }
    }

    private fun restartServer(config: ServerConnection) {
        val reset = disconnectAndReset(clearPendingPastes = false)
        reset.shutdown.whenComplete { _, _ ->
            invokeForConnection(reset.generation) {
                val restarted = config.copy(generation = reset.generation)
                when (restarted.mode) {
                    ConnectionMode.TERMINAL -> createLocalTerminal(restarted)
                    ConnectionMode.REMOTE -> connectToExistingServer(restarted)
                }
            }
        }
    }

    private fun showCliNotFoundError() {
        invokeForConnection(connectionLifecycle.current()) {
            Messages.showErrorDialog(project, "OpenCode V2 CLI (2.x) not found.", "OpenCode")
        }
    }

    private fun showPasteFailure() {
        if (!project.isDisposed) {
            Messages.showWarningDialog(
                project,
                "OpenCode terminal did not become ready in time. Please retry sending the reference.",
                "OpenCode"
            )
        }
    }

    private fun terminateServer(server: Process?) {
        try {
            if (server?.isAlive == true) {
                server.destroy()
                if (!server.waitFor(2, TimeUnit.SECONDS)) server.destroyForcibly()
            }
        } catch (e: Exception) {
            logger.warn("[OpenCode] Could not terminate owned server", e)
        }
    }

    private fun disconnectAndReset(
        clearPendingPastes: Boolean = true, close: Boolean = false
    ): ConnectionReset {
        var shutdown = CompletableFuture.completedFuture<Void>(null)
        val token = connectionLifecycle.reset(close) {
            promptDelivery.pause(clearPendingPastes)
            connectionManagerTask?.cancel(true)
            connectionManagerTask = null
            val previousStream = eventStream
            eventStream = null
            previousStream?.disconnect()
            reviewCoordinator.reset()
            val terminalShutdown = terminalHost.reset()
            val ownedServer = localServerProcess
            localServerProcess = null
            shutdown = terminalShutdown.thenRunAsync(
                { terminateServer(ownedServer) }, AppExecutorUtil.getAppExecutorService()
            )
            connection = null
            apiClient = null
            localServerStarting = false
            isConnected.set(false)
            isConnecting.set(false)
            isConnectionDialogOpen.set(false)
            wasEverConnected = false
            reconnectFailures = 0
            reconnectDialogShown = false
            configureWorkspace(project.basePath)
        }
        return ConnectionReset(token, shutdown)
    }

    override fun dispose() {
        disconnectAndReset(close = true)
    }

    private fun configureWorkspace(path: String?) {
        val normalized = normalizeWorkspace(path ?: project.basePath)
        workspaceRoot = normalized
        sessionManager.configureWorkspaceRoot(normalized)
    }

    private fun resolveWorkspacePath(path: String): String? {
        return PathUtil.resolveWithinRoot(workspaceRoot, path)
    }

    private fun normalizeWorkspace(path: String?): String? {
        if (path.isNullOrBlank()) return null
        return try {
            PathUtil.toSystemIndependentPath(Paths.get(path).normalize().toAbsolutePath().toString())
        } catch (_: Exception) {
            null
        }
    }

    private fun randomPassword(): String {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    companion object {
        private const val DEFAULT_HOST = "127.0.0.1"
        private const val OPEN_CODE_TAB_PREFIX = "OpenCode"
        private const val CONNECTION_RETRY_INTERVAL_MS = 5000L
        private const val SERVER_START_TIMEOUT_MS = 30000L
        private const val DEFAULT_PORT = 4096
        private const val RECONNECT_FAILURE_THRESHOLD = 2
        private val LOCAL_HOSTS = setOf("0.0.0.0", "127.0.0.1", "localhost")

    }
}
