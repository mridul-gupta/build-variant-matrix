package com.nilsenlabs.flavormatrix.actions

import com.intellij.ide.DataManager
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.StatusBarWidgetFactory
import com.intellij.openapi.wm.WindowManager
import java.awt.Component
import java.awt.event.MouseEvent
import java.lang.reflect.Modifier

class BuildVariantStatusBarWidgetFactory : StatusBarWidgetFactory {

    override fun getId(): String = WIDGET_ID

    override fun getDisplayName(): String = "Build Variant Matrix"

    override fun createWidget(project: Project): StatusBarWidget =
        BuildVariantStatusBarWidget(project)

    override fun disposeWidget(widget: StatusBarWidget) = Disposer.dispose(widget)

    override fun isAvailable(project: Project): Boolean = true

    companion object {
        const val WIDGET_ID = "BuildVariantMatrixWidget"

        fun refreshWidget(project: Project) {
            val statusBar = WindowManager.getInstance().getStatusBar(project) ?: return
            val widget = statusBar.getWidget(WIDGET_ID)
            if (widget is BuildVariantStatusBarWidget) {
                widget.refreshVariant()
            }
            statusBar.updateWidget(WIDGET_ID)
        }

        fun setWidgetVariant(project: Project, variantName: String) {
            val statusBar = WindowManager.getInstance().getStatusBar(project) ?: return
            val widget = statusBar.getWidget(WIDGET_ID)
            if (widget is BuildVariantStatusBarWidget) {
                widget.setVariant(variantName)
            }
            statusBar.updateWidget(WIDGET_ID)
        }
    }
}

class BuildVariantStatusBarWidget(
    private val project: Project
) : StatusBarWidget, StatusBarWidget.TextPresentation {

    private var myStatusBar: StatusBar? = null

    @Volatile
    private var displayText: String = "Variant: N/A"

    override fun ID(): String = BuildVariantStatusBarWidgetFactory.WIDGET_ID

    @Suppress("OVERRIDE_DEPRECATION")
    override fun getPresentation(): StatusBarWidget.WidgetPresentation = this

    override fun install(statusBar: StatusBar) {
        myStatusBar = statusBar
        refreshVariant()
        DumbService.getInstance(project).runWhenSmart {
            refreshVariant()
            statusBar.updateWidget(ID())
        }
        ApplicationManager.getApplication().invokeLater({
            refreshVariant()
            statusBar.updateWidget(ID())
        }, project.disposed)
    }

    override fun getText(): String = displayText

    override fun getTooltipText(): String = "Current build variant (click to select)"

    override fun getAlignment(): Float = Component.CENTER_ALIGNMENT

    @Suppress("OVERRIDE_DEPRECATION")
    override fun getClickConsumer(): com.intellij.util.Consumer<MouseEvent>? =
        com.intellij.util.Consumer { _ ->
            val action = ActionManager.getInstance()
                .getAction("SelectBuildVariantMatrixAction") ?: return@Consumer
            val component = myStatusBar?.component ?: return@Consumer
            val dataContext = DataManager.getInstance().getDataContext(component)
            val event = AnActionEvent.createFromAnAction(
                action, null, ActionPlaces.STATUS_BAR_PLACE, dataContext
            )
            action.actionPerformed(event)
        }

    fun setVariant(variantName: String) {
        displayText = "Variant: $variantName"
    }

    fun refreshVariant() {
        val variantName = readVariantFromFacet() ?: readVariantFromModel()
        displayText = if (variantName != null) "Variant: $variantName" else "Variant: N/A"
    }

    private fun findAppModule(): Module? {
        val modules = ModuleManager.getInstance(project).modules
        return modules.firstOrNull { it.name.equals("app", ignoreCase = true) }
            ?: modules.firstOrNull { it.name.endsWith(".app", ignoreCase = true) }
            ?: modules.firstOrNull()
    }

    /**
     * Reads SELECTED_BUILD_VARIANT from AndroidFacet properties via reflection.
     * This is persisted and available immediately on startup (no Gradle sync needed).
     */
    private fun readVariantFromFacet(): String? {
        try {
            val appModule = findAppModule() ?: return null
            return readFacetVariant(appModule)
        } catch (e: Exception) {
            getLog().debug("Failed to read variant from facet: ${e.message}")
        }
        return null
    }

    private fun readVariantFromModel(): String? {
        try {
            val appModule = findAppModule() ?: return null
            val model = try {
                ReflectionAndroidModel.getModel(appModule)
            } catch (_: Throwable) {
                null
            } ?: return null

            val selectedVariant = ReflectionAndroidModel.getSelectedVariant(model) ?: return null
            return ReflectionAndroidModel.getVariantName(selectedVariant)
        } catch (e: Exception) {
            getLog().debug("Failed to read variant from model: ${e.message}")
        }
        return null
    }

    override fun dispose() {}

    companion object {
        private var facetClass: Class<*>? = null
        private var getInstanceMethod: java.lang.reflect.Method? = null
        private var facetInitialized = false

        private fun ensureFacetLoaded() {
            if (facetInitialized) return
            facetInitialized = true
            try {
                facetClass = Class.forName("org.jetbrains.android.facet.AndroidFacet")
                getInstanceMethod = facetClass?.methods?.firstOrNull { m ->
                    m.name == "getInstance" &&
                    m.parameterCount == 1 &&
                    Module::class.java.isAssignableFrom(m.parameterTypes[0]) &&
                    Modifier.isStatic(m.modifiers)
                }
            } catch (_: Throwable) { }
        }

        fun readFacetVariant(module: Module): String? {
            ensureFacetLoaded()
            val facet = getInstanceMethod?.invoke(null, module) ?: return null
            val properties = facet.javaClass.methods
                .firstOrNull { it.name == "getProperties" && it.parameterCount == 0 }
                ?.invoke(facet) ?: return null
            val variant = properties.javaClass.getField("SELECTED_BUILD_VARIANT")
                .get(properties)?.toString()
            return variant?.takeIf { it.isNotBlank() }
        }
    }
}
