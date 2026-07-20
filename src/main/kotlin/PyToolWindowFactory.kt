package xyz.shaggysa

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.content.ContentFactory
import javax.swing.JButton
import kotlin.random.Random

class PyToolWindowFactory : ToolWindowFactory {
    override fun shouldBeAvailable(project: Project) = true

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val pyToolWindow = PyToolWindow()
        val content = ContentFactory.getInstance().createContent(pyToolWindow.getContent(), null, false)
        toolWindow.contentManager.addContent(content)
    }

    class PyToolWindow {
        private val content = JBPanel<JBPanel<*>>().apply {
            val label = JBLabel(PyMessageBundle.message("toolwindow.PyToolWindow.number.label", "?"))

            add(label)
            add(JButton(PyMessageBundle.message("toolwindow.PyToolWindow.shuffle.button")).apply {
                addActionListener {
                    label.text = PyMessageBundle.message(
                        "toolwindow.PyToolWindow.number.label", Random(System.currentTimeMillis()).nextInt(1000)
                    )
                }
            })
        }

        fun getContent(): JBPanel<JBPanel<*>> = content
    }
}
