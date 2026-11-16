package com.adm.clickgui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import com.adm.features.Module
import com.adm.utils.ui.compose.UiHost
import kotlin.properties.PropertyDelegateProvider
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

abstract class Setting<T>(
    val name: String,
    var description: String
) : ReadWriteProperty<Module, T>, PropertyDelegateProvider<Module, ReadWriteProperty<Module, T>> {

    abstract val default: T
    abstract var value: T

    fun reset() {
        value = default
    }

    override operator fun provideDelegate(thisRef: Module, property: KProperty<*>): ReadWriteProperty<Module, T> =
        thisRef.registerSetting(this)

    override operator fun getValue(thisRef: Module, property: KProperty<*>): T = value

    override operator fun setValue(thisRef: Module, property: KProperty<*>, value: T) {
        this.value = value
    }
}

// Game code reads the plain field (no snapshot lookup); only composition reads the state, so the GUI still recomposes on changes.
class SettingState<T>(initial: T) : ReadWriteProperty<Any?, T> {
    @Volatile
    private var plain = initial
    private val state = mutableStateOf(initial)

    override fun getValue(thisRef: Any?, property: KProperty<*>): T = if (UiHost.composing > 0) state.value else plain

    override fun setValue(thisRef: Any?, property: KProperty<*>, value: T) {
        plain = value
        state.value = value
    }
}

abstract class RenderableSetting<T>(name: String, description: String) : Setting<T>(name, description) {
    var hidden: Boolean = false
    var visibilityDependency: (() -> Boolean)? = null

    val isVisible: Boolean
        get() = !hidden && visibilityDependency?.invoke() != false


    fun hide(): Setting<T> {
        hidden = true
        return this
    }

    @Composable
    abstract fun Content()

    companion object {
        fun <K : RenderableSetting<*>> K.withDependency(dependency: () -> Boolean): K {
            visibilityDependency = dependency
            return this
        }
    }
}