package ai.opencode.ide.jetbrains.ui

import ai.opencode.ide.jetbrains.util.NetworkAddress
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
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import java.io.File
import javax.swing.ButtonGroup
import javax.swing.JComponent
import javax.swing.JRadioButton

class OpenCodeConnectDialog(
    private val project: Project,
    private val retry: ConnectionInfo.ExistingServer? = null
) : DialogWrapper(project, true) {

    sealed interface ConnectionInfo {
        val customBasePath: String?

        data class StartLocal(override val customBasePath: String?) : ConnectionInfo

        data class ExistingServer(
            val hostname: String,
            val port: Int,
            val password: String?,
            override val customBasePath: String?
        ) : ConnectionInfo
    }

    private val startLocal = JRadioButton("Start a local server", retry == null)
    private val connectExisting = JRadioButton("Connect to an existing server", retry != null)
    private val addressField = JBTextField(
        retry?.let { NetworkAddress.httpBaseUrl(it.hostname, it.port).removePrefix("http://") }
            ?: "127.0.0.1:4096"
    ).apply { columns = 24 }
    private val passwordField = JBPasswordField()
    private val basePathField = ComboBox<String>().apply { isEditable = true }
    internal var choice: ConnectionInfo? = null
        private set

    init {
        title = "Open OpenCode V2"
        setOKButtonText(if (connectExisting.isSelected) "Connect" else "Start")
        setCancelButtonText("Close")
        init()
    }

    override fun createCenterPanel(): JComponent {
        ButtonGroup().apply {
            add(startLocal)
            add(connectExisting)
        }

        val savedPath = retry?.customBasePath
            ?: PropertiesComponent.getInstance(project).getValue(PROP_CUSTOM_BASE_PATH, "")
        ModuleManager.getInstance(project).modules
            .mapNotNull { it.guessModuleDir()?.path }.sorted().forEach(basePathField::addItem)
        if (savedPath.isNotBlank()) basePathField.item = savedPath
        else basePathField.selectedIndex = -1

        val password = if (retry == null) loadPassword() else retry.password
        password?.let { passwordField.text = it }
        addressField.toolTipText = "Format: hostname:port (e.g., 127.0.0.1:4096)"
        passwordField.emptyText.text = "For an existing protected server"
        basePathField.toolTipText = "Working directory for the OpenCode terminal and workspace"

        val existingFields = FormBuilder.createFormBuilder()
            .addLabeledComponent("Server address:", addressField)
            .addLabeledComponent("Server password:", passwordField)
            .panel
        existingFields.isVisible = connectExisting.isSelected
        connectExisting.addItemListener {
            existingFields.isVisible = connectExisting.isSelected
            setOKButtonText(if (connectExisting.isSelected) "Connect" else "Start")
            pack()
        }

        return FormBuilder.createFormBuilder()
            .addComponent(startLocal)
            .addComponent(connectExisting)
            .addComponent(existingFields)
            .addLabeledComponent("Custom base path (optional):", basePathField)
            .panel
    }

    override fun getPreferredFocusedComponent(): JComponent =
        if (connectExisting.isSelected) addressField else startLocal

    override fun doValidate(): ValidationInfo? {
        if (connectExisting.isSelected && parseAddress(addressField.text) == null) {
            return ValidationInfo("Use hostname:port or [IPv6]:port with a valid port", addressField)
        }
        val path = customBasePath()
        if (path != null && !File(path).isDirectory) {
            return ValidationInfo("Custom base path must be an existing directory", basePathField)
        }
        return null
    }

    override fun doOKAction() {
        if (doValidate() != null) return
        val path = customBasePath()
        val selected = if (connectExisting.isSelected) {
            val (hostname, port) = parseAddress(addressField.text) ?: return
            val password = passwordField.password.concatToString().trim().ifBlank { null }
            try {
                PasswordSafe.instance.set(credentialAttributes(), password?.let { Credentials(CREDENTIAL_USERNAME, it) })
            } catch (_: Exception) {
                // A locked credential store must not prevent connecting.
            }
            ConnectionInfo.ExistingServer(hostname, port, password, path)
        } else {
            ConnectionInfo.StartLocal(path)
        }

        val props = PropertiesComponent.getInstance(project)
        if (path != null) props.setValue(PROP_CUSTOM_BASE_PATH, path)
        else props.unsetValue(PROP_CUSTOM_BASE_PATH)

        choice = selected
        super.doOKAction()
    }

    private fun customBasePath(): String? =
        (basePathField.editor.item as? String)?.trim()?.ifBlank { null }

    private fun loadPassword(): String? = try {
        PasswordSafe.instance.get(credentialAttributes())?.password?.toString()
    } catch (_: Exception) {
        null
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
        val port = input.substring(separator + 1).trim().toIntOrNull() ?: return null
        if (host.isBlank() || port !in 1..65535) return null
        return host to port
    }

    companion object {
        private const val PROP_CUSTOM_BASE_PATH = "opencode.customBasePath"
        private const val CREDENTIAL_SERVICE = "OpenCode UI server"
        private const val CREDENTIAL_USERNAME = "opencode"

        private fun credentialAttributes() = CredentialAttributes(CREDENTIAL_SERVICE)

        fun show(project: Project, retry: ConnectionInfo.ExistingServer? = null): ConnectionInfo? {
            val dialog = OpenCodeConnectDialog(project, retry)
            return if (dialog.showAndGet()) dialog.choice else null
        }
    }
}
