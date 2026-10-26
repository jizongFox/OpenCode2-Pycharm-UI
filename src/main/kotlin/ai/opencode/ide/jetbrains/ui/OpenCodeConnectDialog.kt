package ai.opencode.ide.jetbrains.ui

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.Credentials
import com.intellij.ide.passwordSafe.PasswordSafe
import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessModuleDir
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.GridLayout
import java.io.File
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * Dialog for configuring OpenCode server connection.
 * Allows user to specify host:port before starting the terminal.
 */
class OpenCodeConnectDialog(
    private val project: Project,
    private val defaultPort: Int
) : DialogWrapper(project, true) {

    data class ConnectionInfo(
        val hostname: String,
        val port: Int,
        val password: String?,
        val customBasePath: String? = null
    )

    private val addressField = JBTextField("127.0.0.1:$defaultPort")
    private val passwordField = JBPasswordField()
    private val basePathField = ComboBox<String>().apply {
        isEditable = true
    }
    
    var hostname: String = "127.0.0.1"
        private set
    var port: Int = defaultPort
        private set
    var password: String? = null
        private set
    var customBasePath: String? = null
        private set
    init {
        title = "Connect to OpenCode V2"
        setOKButtonText("Connect")
        setCancelButtonText("Close")
        init()
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(BorderLayout(0, JBUI.scale(8)))
        panel.preferredSize = Dimension(JBUI.scale(350), JBUI.scale(180))

        val formPanel = JPanel(GridLayout(6, 1, 0, JBUI.scale(4)))
        val addressLabel = JBLabel("Server address:")
        val passwordLabel = JBLabel("Server password (optional):")
        val basePathLabel = JBLabel("Custom base path (optional):")

        // Load saved values
        val props = PropertiesComponent.getInstance(project)
        // Always suggest a new available port by default
        addressField.text = "127.0.0.1:$defaultPort"
        
        loadPassword()?.let { passwordField.text = it }

        // Load saved custom base path
        val savedPath = props.getValue(PROP_CUSTOM_BASE_PATH, "")

        // Populate module paths
        val moduleManager = ModuleManager.getInstance(project)
        val modules = moduleManager.modules
        val paths = modules.mapNotNull { it.guessModuleDir()?.path }.sorted()
        paths.forEach { basePathField.addItem(it) }

        if (savedPath.isNotBlank()) {
            basePathField.item = savedPath
        } else {
            basePathField.selectedIndex = -1
        }

        addressField.toolTipText = "Format: hostname:port (e.g., 127.0.0.1:4096)"
        passwordField.toolTipText = "OPENCODE_PASSWORD"
        passwordField.emptyText.text = "For remote OpenCode servers"
        basePathField.toolTipText = "Override project base path for opencode.exe working directory"

        formPanel.add(addressLabel)
        formPanel.add(addressField)
        formPanel.add(passwordLabel)
        formPanel.add(passwordField)
        formPanel.add(basePathLabel)
        formPanel.add(basePathField)

        panel.add(formPanel, BorderLayout.CENTER)

        return panel
    }

    override fun getPreferredFocusedComponent(): JComponent = addressField

    override fun doValidate(): ValidationInfo? {
        val input = addressField.text.trim()
        
        if (input.isBlank()) {
            return ValidationInfo("Server address cannot be empty", addressField)
        }
        
        if (parseAddress(input) == null) {
            return ValidationInfo("Use hostname:port or [IPv6]:port with a valid port", addressField)
        }

        val basePath = (basePathField.editor.item as? String)?.trim().orEmpty()
        if (basePath.isNotEmpty() && !File(basePath).isDirectory) {
            return ValidationInfo("Custom base path must be an existing directory", basePathField)
        }
        
        return null
    }

    override fun doOKAction() {
        val input = addressField.text.trim()
        val address = parseAddress(input) ?: return
        hostname = address.first
        port = address.second

        val passwordValue = passwordField.password.concatToString().trim()
        password = passwordValue.ifBlank { null }
        
        val basePathValue = (basePathField.editor.item as? String)?.trim() ?: ""
        customBasePath = basePathValue.ifBlank { null }

        // Save values for next time
        val props = PropertiesComponent.getInstance(project)

        try {
            val credentials = password?.let { Credentials(CREDENTIAL_USERNAME, it) }
            PasswordSafe.instance.set(credentialAttributes(), credentials)
        } catch (e: Exception) {
            // A locked credential store must not prevent connecting.
        }

        // Save custom base path
        if (!customBasePath.isNullOrBlank()) {
            props.setValue(PROP_CUSTOM_BASE_PATH, customBasePath)
        } else {
            props.unsetValue(PROP_CUSTOM_BASE_PATH)
        }

        super.doOKAction()
    }

    private fun loadPassword(): String? {
        return try {
            PasswordSafe.instance.get(credentialAttributes())?.password?.toString()
        } catch (_: Exception) {
            null
        }
    }

    private fun parseAddress(value: String): Pair<String, Int>? {
        val input = value.trim()
        val separator = when {
            input.startsWith("[") -> {
                val bracket = input.indexOf(']')
                if (bracket <= 1 || bracket + 1 >= input.length || input[bracket + 1] != ':') return null
                bracket + 1
            }
            input.count { it == ':' } == 1 -> input.lastIndexOf(':')
            else -> return null
        }
        val host = input.substring(0, separator).trim().removePrefix("[").removeSuffix("]")
        val parsedPort = input.substring(separator + 1).trim().toIntOrNull() ?: return null
        if (host.isBlank() || parsedPort !in 1..65535) return null
        return host to parsedPort
    }

    companion object {
        private const val PROP_CUSTOM_BASE_PATH = "opencode.customBasePath"
        private const val CREDENTIAL_SERVICE = "OpenCode UI server"
        private const val CREDENTIAL_USERNAME = "opencode"

        private fun credentialAttributes(): CredentialAttributes {
            return CredentialAttributes(CREDENTIAL_SERVICE)
        }
        
        /**
         * Shows the dialog and returns the result.
         * @return ConnectionInfo if user clicked Connect, null if cancelled
         */
        fun show(project: Project, defaultPort: Int): ConnectionInfo? {
            val dialog = OpenCodeConnectDialog(project, defaultPort)
            return if (dialog.showAndGet()) {
                ConnectionInfo(
                    dialog.hostname,
                    dialog.port,
                    dialog.password,
                    dialog.customBasePath
                )
            } else {
                null
            }
        }
    }
}
