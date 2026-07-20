package xyz.shaggysa

import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.ui.content.ContentFactory
import com.intellij.util.ui.JBUI
import java.awt.*
import java.io.File
import javax.swing.*
import javax.swing.border.TitledBorder

fun findUvxExecutable(): String {
    val paths = listOf(
        System.getProperty("user.home") + "/.local/bin/uvx",
        System.getProperty("user.home") + "/.cargo/bin/uvx",
        "/usr/local/bin/uvx",
        "/usr/bin/uvx",
        "/bin/uvx",
        "/opt/homebrew/bin/uvx"
    )
    for (path in paths) {
        val file = File(path)
        if (file.exists() && file.canExecute()) {
            return path
        }
    }
    return "uvx"
}

class PyToolWindowFactory : ToolWindowFactory {
    override fun shouldBeAvailable(project: Project) = true

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val pyToolWindow = PyToolWindow(project)
        val content = ContentFactory.getInstance().createContent(pyToolWindow.getContent(), null, false)
        toolWindow.contentManager.addContent(content)
        
        pyToolWindow.startSubprocess()
    }

    class ConnectionDialog(
        project: Project,
        defaultType: ConnectionType,
        defaultHubName: String?
    ) : DialogWrapper(project) {
        val connTypeCombo = ComboBox(arrayOf("Bluetooth", "USB")).apply {
            selectedIndex = if (defaultType == ConnectionType.Bluetooth) 0 else 1
        }
        val hubNameField = JBTextField(defaultHubName ?: "")

        init {
            title = "Connect to Pybricks Hub"
            init()
        }

        override fun createCenterPanel(): JComponent {
            val panel = JPanel(GridBagLayout())
            val gbc = GridBagConstraints().apply {
                insets = JBUI.insets(6)
                fill = GridBagConstraints.HORIZONTAL
            }
            
            gbc.gridx = 0
            gbc.gridy = 0
            gbc.weightx = 0.0
            panel.add(JLabel("Connection Type:"), gbc)
            
            gbc.gridx = 1
            gbc.weightx = 1.0
            panel.add(connTypeCombo, gbc)

            gbc.gridx = 0
            gbc.gridy = 1
            gbc.weightx = 0.0
            panel.add(JLabel("Hub Name (Optional):"), gbc)
            
            gbc.gridx = 1
            gbc.weightx = 1.0
            panel.add(hubNameField, gbc)

            return panel
        }
    }

    class PyToolWindow(private val project: Project) {
        private val stateService = project.getService(PyStateService::class.java)

        private var uvxPath = findUvxExecutable()
        private var isManuallyStopped = false
        private var consecutiveCrashes = 0

        private var isConnected = false
        private var isConnecting = false
        private var isProgramRunning = false
        private var selectedType = ConnectionType.Bluetooth
        private var selectedHubName: String? = null
        
        private var pendingAction: Runnable? = null

        private val programPathField = JBTextField()
        private val chooseFileBtn = JButton(PyMessageBundle.message("pybricks.program.choose"))
        private val useActiveFileBtn = JButton(PyMessageBundle.message("pybricks.program.use_current"))

        private val downloadBtn = JButton(PyMessageBundle.message("pybricks.control.download"))
        private val runStoredBtn = JButton(PyMessageBundle.message("pybricks.control.run"))
        private val downloadRunBtn = JButton(PyMessageBundle.message("pybricks.control.download_run"))
        private val cancelBtn = JButton(PyMessageBundle.message("pybricks.control.cancel"))

        private val clearConsoleBtn = JButton(PyMessageBundle.message("pybricks.terminal.clear"))
        private val terminalArea = JBTextArea().apply {
            isEditable = false
            font = Font("Monospaced", Font.PLAIN, 12)
            lineWrap = true
            wrapStyleWord = true
        }

        private val statusLabel = JBLabel(PyMessageBundle.message("pybricks.status.label") + " Disconnected")
        private val progressBar = JProgressBar(0, 100).apply {
            isStringPainted = true
            string = ""
            isVisible = false
        }

        private val mainPanel = JBPanel<JBPanel<*>>(GridBagLayout())
        private val topConnectionPanel = JBPanel<JBPanel<*>>(BorderLayout()).apply {
            border = BorderFactory.createEmptyBorder(0, 0, 8, 0)
        }

        private val eventListener = { event: IncomingEvent ->
            SwingUtilities.invokeLater {
                handleIncomingEvent(event)
            }
        }
        private val errorListener = { error: Throwable ->
            SwingUtilities.invokeLater {
                handleProcessError(error)
            }
        }
        private val terminationListener = { exitCode: Int ->
            SwingUtilities.invokeLater {
                handleProcessTermination(exitCode)
            }
        }

        init {
            setupLayout()
            setupInteractions()
            updateControlsState()
            updateTopConnectionPanel()
        }

        fun getContent(): JBPanel<JBPanel<*>> = mainPanel

        private fun setupLayout() {
            mainPanel.border = BorderFactory.createEmptyBorder(10, 10, 10, 10)
            val gbc = GridBagConstraints().apply {
                fill = GridBagConstraints.HORIZONTAL
                gridx = 0
                weightx = 1.0
                insets = JBUI.insets(4)
            }

            gbc.gridy = 0
            mainPanel.add(topConnectionPanel, gbc)

            // Program Panel
            val progPanel = JBPanel<JBPanel<*>>(GridBagLayout()).apply {
                border = BorderFactory.createTitledBorder(
                    BorderFactory.createEtchedBorder(),
                    PyMessageBundle.message("pybricks.program.title"),
                    TitledBorder.LEFT,
                    TitledBorder.TOP
                )
            }
            val progGbc = GridBagConstraints().apply {
                insets = JBUI.insets(2, 4)
                fill = GridBagConstraints.HORIZONTAL
            }
            progGbc.gridx = 0
            progGbc.weightx = 0.0
            progPanel.add(JBLabel("File:"), progGbc)
            progGbc.gridx = 1
            progGbc.weightx = 1.0
            progPanel.add(programPathField, progGbc)
            progGbc.gridx = 2
            progGbc.weightx = 0.0
            progPanel.add(chooseFileBtn, progGbc)

            val progBtnPanel = JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.LEFT, 4, 0))
            progBtnPanel.add(useActiveFileBtn)

            progGbc.gridx = 0
            progGbc.gridy = 1
            progGbc.gridwidth = 3
            progPanel.add(progBtnPanel, progGbc)

            gbc.gridy = 1
            mainPanel.add(progPanel, gbc)

            // Controls Panel
            val ctrlPanel = JBPanel<JBPanel<*>>(GridLayout(2, 2, 6, 6)).apply {
                border = BorderFactory.createTitledBorder(
                    BorderFactory.createEtchedBorder(),
                    PyMessageBundle.message("pybricks.control.title"),
                    TitledBorder.LEFT,
                    TitledBorder.TOP
                )
                add(downloadBtn)
                add(downloadRunBtn)
                add(runStoredBtn)
                add(cancelBtn)
            }
            gbc.gridy = 2
            mainPanel.add(ctrlPanel, gbc)

            // Terminal Panel
            val termPanel = JBPanel<JBPanel<*>>(BorderLayout()).apply {
                border = BorderFactory.createTitledBorder(
                    BorderFactory.createEtchedBorder(),
                    PyMessageBundle.message("pybricks.terminal.title"),
                    TitledBorder.LEFT,
                    TitledBorder.TOP
                )
            }
            val termBar = JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.RIGHT, 4, 0))
            termBar.add(clearConsoleBtn)
            termPanel.add(termBar, BorderLayout.NORTH)
            termPanel.add(JBScrollPane(terminalArea), BorderLayout.CENTER)

            gbc.gridy = 3
            gbc.weighty = 1.0
            gbc.fill = GridBagConstraints.BOTH
            mainPanel.add(termPanel, gbc)

            // Status and Progress Row
            val statusPanel = JBPanel<JBPanel<*>>(BorderLayout()).apply {
                border = BorderFactory.createEmptyBorder(4, 4, 4, 4)
            }
            statusPanel.add(statusLabel, BorderLayout.WEST)
            statusPanel.add(progressBar, BorderLayout.CENTER)

            gbc.gridy = 4
            gbc.weighty = 0.0
            gbc.fill = GridBagConstraints.HORIZONTAL
            mainPanel.add(statusPanel, gbc)
        }

        private fun updateTopConnectionPanel() {
            topConnectionPanel.removeAll()
            
            val innerPanel = JPanel(BorderLayout()).apply {
                border = BorderFactory.createCompoundBorder(
                    BorderFactory.createEtchedBorder(),
                    BorderFactory.createEmptyBorder(6, 10, 6, 10)
                )
            }
            
            if (isConnected) {
                val hubDesc = if (selectedHubName != null) "$selectedHubName (${selectedType.name})" else "Hub (${selectedType.name})"
                innerPanel.add(JLabel("Connected to $hubDesc"), BorderLayout.WEST)
                
                val disconnectBtn = JButton("Disconnect").apply {
                    addActionListener {
                        try {
                            stateService.pyState?.sendEvent(OutgoingEvent.DisconnectFromHub)
                        } catch (e: Exception) {
                            Messages.showErrorDialog(project, "Could not send disconnect command:\n${e.localizedMessage}", "Disconnect Error")
                        }
                    }
                }
                innerPanel.add(disconnectBtn, BorderLayout.EAST)
            } else if (isConnecting) {
                innerPanel.add(JLabel("Connecting to hub..."), BorderLayout.WEST)
                
                val cancelBtn = JButton("Cancel").apply {
                    addActionListener {
                        try {
                            stateService.pyState?.sendEvent(OutgoingEvent.DisconnectFromHub)
                            isConnecting = false
                            updateStatus("Disconnected")
                            updateControlsState()
                            updateTopConnectionPanel()
                        } catch (_: Exception) {
                            // ignore
                        }
                    }
                }
                innerPanel.add(cancelBtn, BorderLayout.EAST)
            } else {
                innerPanel.add(JLabel("Not connected to a hub"), BorderLayout.WEST)
                
                val connectBtn = JButton("Connect to Hub...").apply {
                    addActionListener {
                        ensureConnected {}
                    }
                }
                innerPanel.add(connectBtn, BorderLayout.EAST)
            }
            
            topConnectionPanel.add(innerPanel, BorderLayout.CENTER)
            topConnectionPanel.revalidate()
            topConnectionPanel.repaint()
        }

        private fun ensureConnected(onConnected: Runnable) {
            val activeState = stateService.pyState
            if (activeState == null || !activeState.isRunning) {
                Messages.showErrorDialog(project, "The Pybricks Runner background process is not running.", "Subprocess Error")
                return
            }

            if (isConnected) {
                onConnected.run()
                return
            }
            
            val dialog = ConnectionDialog(project, selectedType, selectedHubName)
            if (dialog.showAndGet()) {
                selectedType = if (dialog.connTypeCombo.selectedIndex == 0) ConnectionType.Bluetooth else ConnectionType.Usb
                selectedHubName = dialog.hubNameField.text.trim().let { if (it.isEmpty()) null else it }
                
                isConnecting = true
                updateStatus("Connecting...")
                updateControlsState()
                updateTopConnectionPanel()
                
                // Store the pending action to execute once connection is established
                pendingAction = onConnected
                
                try {
                    activeState.sendEvent(OutgoingEvent.ConnectToHub(selectedType, selectedHubName))
                } catch (e: Exception) {
                    isConnecting = false
                    pendingAction = null
                    updateStatus("Disconnected")
                    updateControlsState()
                    updateTopConnectionPanel()
                    Messages.showErrorDialog(project, "Could not send connect command:\n${e.localizedMessage}", "Connection Error")
                }
            }
        }

        private fun setupInteractions() {
            chooseFileBtn.addActionListener {
                val descriptor = FileChooserDescriptorFactory.createSingleFileNoJarsDescriptor().withTitle("Select Python Program File")
                val selectedFile = FileChooser.chooseFile(descriptor, project, null)
                if (selectedFile != null) {
                    programPathField.text = selectedFile.path
                }
            }

            useActiveFileBtn.addActionListener {
                val virtualFile = FileEditorManager.getInstance(project).selectedFiles.firstOrNull()
                if (virtualFile != null) {
                    if (virtualFile.extension == "py") {
                        programPathField.text = virtualFile.path
                    } else {
                        Messages.showWarningDialog(project, "The active editor file is not a Python (.py) file.", "Invalid File Type")
                    }
                } else {
                    Messages.showWarningDialog(project, "No active file open in the editor.", "No Active File")
                }
            }

            downloadBtn.addActionListener {
                val path = programPathField.text.trim()
                if (path.isEmpty()) {
                    Messages.showWarningDialog(project, "Please select a Python file to download.", "No File Selected")
                    return@addActionListener
                }
                ensureConnected {
                    try {
                        stateService.pyState?.sendEvent(OutgoingEvent.RecompileDownload(path))
                    } catch (e: Exception) {
                        Messages.showErrorDialog(project, "Could not send download command:\n${e.localizedMessage}", "Error")
                    }
                }
            }

            downloadRunBtn.addActionListener {
                val path = programPathField.text.trim()
                if (path.isEmpty()) {
                    Messages.showWarningDialog(project, "Please select a Python file to recompile and run.", "No File Selected")
                    return@addActionListener
                }
                ensureConnected {
                    try {
                        stateService.pyState?.sendEvent(OutgoingEvent.RecompileRun(path))
                    } catch (e: Exception) {
                        Messages.showErrorDialog(project, "Could not send download and run command:\n${e.localizedMessage}", "Error")
                    }
                }
            }

            runStoredBtn.addActionListener {
                ensureConnected {
                    try {
                        stateService.pyState?.sendEvent(OutgoingEvent.RunStored)
                    } catch (e: Exception) {
                        Messages.showErrorDialog(project, "Could not send run stored command:\n${e.localizedMessage}", "Error")
                    }
                }
            }

            cancelBtn.addActionListener {
                ensureConnected {
                    try {
                        stateService.pyState?.sendEvent(OutgoingEvent.CancelRunningProgram)
                    } catch (e: Exception) {
                        Messages.showErrorDialog(project, "Could not send cancel command:\n${e.localizedMessage}", "Error")
                    }
                }
            }

            clearConsoleBtn.addActionListener {
                terminalArea.text = ""
            }
        }

        fun startSubprocess() {
            if (uvxPath.isEmpty()) {
                promptForUvx()
                return
            }
            try {
                isManuallyStopped = false
                val newState = stateService.getOrCreatePyState(uvxPath)
                
                newState.addEventListener(eventListener)
                newState.addErrorListener(errorListener)
                newState.addTerminationListener(terminationListener)

                newState.start()

                isConnected = false
                isConnecting = false
                isProgramRunning = false

                updateStatus("Ready")
                updateControlsState()
                updateTopConnectionPanel()
            } catch (e: Exception) {
                updateStatus("Process Failed to Start")
                updateControlsState()
                updateTopConnectionPanel()
                promptForUvx()
            }
        }

        private fun promptForUvx() {
            SwingUtilities.invokeLater {
                val newPath = Messages.showInputDialog(
                    project,
                    "Pybricks Runner requires 'uvx' to run. It was not found in standard locations.\n" +
                    "If uvx is installed in a custom location, please provide its path below:",
                    "uvx Executable Required",
                    Messages.getQuestionIcon(),
                    uvxPath,
                    null
                )
                if (newPath != null && newPath.trim().isNotEmpty()) {
                    uvxPath = newPath.trim()
                    startSubprocess()
                } else {
                    updateStatus("Missing uvx")
                    updateControlsState()
                }
            }
        }

        private fun updateStatus(statusText: String) {
            statusLabel.text = PyMessageBundle.message("pybricks.status.label") + " " + statusText
        }

        private fun handleIncomingEvent(event: IncomingEvent) {
            // Reset consecutive crash count on first successful event from brickpipe
            consecutiveCrashes = 0

            when (event) {
                is IncomingEvent.BleDeviceFound -> {
                    // Do not log or show BLE scans to the console
                }
                is IncomingEvent.HubConnected -> {
                    isConnected = true
                    isConnecting = false
                    isProgramRunning = false
                    updateStatus("Connected")
                    updateControlsState()
                    updateTopConnectionPanel()
                    
                    // Trigger pending actions
                    val action = pendingAction
                    pendingAction = null
                    action?.run()
                }
                is IncomingEvent.ConnectionTimeout -> {
                    isConnected = false
                    isConnecting = false
                    isProgramRunning = false
                    pendingAction = null
                    updateStatus("Connection Timeout")
                    updateControlsState()
                    updateTopConnectionPanel()
                    Messages.showErrorDialog(project, "Connection to the Pybricks Hub timed out.", "Connection Timeout")
                }
                is IncomingEvent.DownloadProgressUpdate -> {
                    if (event.percentage.toInt() == 100) {
                        progressBar.isVisible = false
                        updateStatus("Connected")
                    } else {
                        progressBar.isVisible = true
                        progressBar.value = event.percentage.toInt()
                        progressBar.string = "Downloading: ${String.format("%.1f", event.percentage)}%"
                        updateStatus("Downloading...")
                    }
                }
                is IncomingEvent.ProgramStarted -> {
                    isProgramRunning = true
                    updateStatus("Running Program")
                    updateControlsState()
                    updateTopConnectionPanel()
                }
                is IncomingEvent.ProgramComplete -> {
                    isProgramRunning = false
                    updateStatus("Connected")
                    updateControlsState()
                    updateTopConnectionPanel()
                }
                is IncomingEvent.HubPrintedLine -> {
                    terminalArea.append(event.line + "\n")
                    terminalArea.caretPosition = terminalArea.document.length
                }
                is IncomingEvent.CompileError -> {
                    isProgramRunning = false
                    updateStatus("Connected")
                    // Hide progress bar on compilation error
                    progressBar.isVisible = false
                    progressBar.value = 0
                    progressBar.string = "Compile failed"
                    updateControlsState()
                    updateTopConnectionPanel()
                    Messages.showErrorDialog(project, "Compilation failed:\n\n${event.traceback}", "Compilation Error")
                }
                is IncomingEvent.HubFirmwareOutdated -> {
                    isConnected = false
                    isConnecting = false
                    isProgramRunning = false
                    pendingAction = null
                    updateStatus("Disconnected")
                    progressBar.isVisible = false
                    updateControlsState()
                    updateTopConnectionPanel()
                    Messages.showWarningDialog(project, "Hub firmware is outdated:\n${event.explanation}", "Firmware Outdated")
                }
                is IncomingEvent.PreconditionViolated -> {
                    Messages.showWarningDialog(project, "Precondition violated:\n${event.explanation}", "Precondition Violated")
                }
                is IncomingEvent.HubDisconnected -> {
                    isConnected = false
                    isConnecting = false
                    isProgramRunning = false
                    pendingAction = null
                    updateStatus("Disconnected")
                    progressBar.isVisible = false
                    progressBar.value = 0
                    progressBar.string = ""
                    updateControlsState()
                    updateTopConnectionPanel()
                }
            }
        }

        private fun handleProcessError(error: Throwable) {
            isConnected = false
            isConnecting = false
            isProgramRunning = false
            progressBar.isVisible = false
            updateStatus("Process Error")
            updateControlsState()
            updateTopConnectionPanel()
            promptForUvx()
        }

        private fun handleProcessTermination(exitCode: Int) {
            isConnected = false
            isConnecting = false
            isProgramRunning = false
            progressBar.value = 0
            progressBar.string = ""
            progressBar.isVisible = false
            updateStatus("Process Terminated")
            updateControlsState()
            updateTopConnectionPanel()

            if (!isManuallyStopped) {
                consecutiveCrashes++
                if (consecutiveCrashes <= 3) {
                    // Automatically restart the subprocess
                    val timer = Timer(1000) {
                        if (!isManuallyStopped) {
                            startSubprocess()
                        }
                    }
                    timer.isRepeats = false
                    timer.start()
                } else {
                    consecutiveCrashes = 0
                    Messages.showErrorDialog(
                        project,
                        "The Pybricks background process (brickpipe) has exited unexpectedly.\n" +
                        "Please ensure 'uvx' is installed and working.",
                        "Subprocess Failure"
                    )
                }
            }
        }

        private fun updateControlsState() {
            val activeState = stateService.pyState
            val isProcessRunning = activeState != null && activeState.isRunning

            // Program selection
            programPathField.isEditable = isProcessRunning && !isProgramRunning
            chooseFileBtn.isEnabled = isProcessRunning && !isProgramRunning
            useActiveFileBtn.isEnabled = isProcessRunning && !isProgramRunning

            // Command buttons
            downloadBtn.isEnabled = isProcessRunning && !isProgramRunning
            downloadRunBtn.isEnabled = isProcessRunning && !isProgramRunning
            runStoredBtn.isEnabled = isProcessRunning && !isProgramRunning
            cancelBtn.isEnabled = isProcessRunning && isProgramRunning
        }
    }
}
