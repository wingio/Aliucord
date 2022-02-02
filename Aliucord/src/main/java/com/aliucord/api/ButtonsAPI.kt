/*
 * Copyright (c) 2021 Juby210 & Vendicated
 * Licensed under the Open Software License version 3.0
 */

package com.aliucord.api

import android.content.Context
import androidx.fragment.app.FragmentManager

import com.aliucord.Logger
import com.aliucord.utils.ReflectUtils
import com.aliucord.coreplugins.ButtonsAPI

import com.discord.api.botuikit.*
import com.discord.models.message.Message
import com.discord.stores.StoreStream

import java.util.*
import java.lang.reflect.*
import kotlin.collections.lastOrNull
import kotlin.properties.ReadOnlyProperty
import kotlin.reflect.KProperty

/**
 * Adds methods for creating button components
 */
@Suppress("UNCHECKED_CAST")
object ButtonsAPI {

    /**
     * Stores data about a button
     */
    data class ButtonData(val label: String, val style: ButtonStyle, val onPress: (Message, FragmentManager) -> Unit)

    class LazyField<T>(private val clazz : Class<*>, private val field: String? ) : ReadOnlyProperty<T, Field> {
        private var v = null as Field?
        override fun getValue(thisRef: T, property: KProperty<*>) = v ?: clazz.getDeclaredField(field ?: property.name.replace("Field", "")).apply {
            setAccessible(true)
            v = this
        }
    }

    inline fun <reified T> lazyField(field: String? = null) = LazyField<Any>(T::class.java, field)

    private val componentsField by lazyField<ActionRowComponent>()
    private val msgComponentsField by lazyField<Message>("components")
    private val arTypeField by lazyField<ActionRowComponent>("type")

    private val labelField by lazyField<ButtonComponent>()
    private val styleField by lazyField<ButtonComponent>()
    private val disabledField by lazyField<ButtonComponent>()
    private val idField by lazyField<ButtonComponent>("customId")
    private val typeField by lazyField<ButtonComponent>()

    /**
     * Creates a button with the given data
     * @param button The data to create the button with
     */
    @JvmStatic
    fun Message.addButton(button: ButtonData) {
        this.addButton(button.label, button.style, button.onPress)
    }

    /**
     * Adds a button component to the message.
     * @param label   The label of the button.
     * @param style   The style of the button. {@link ButtonStyle}
     * @param onPress Callback for when the button is pressed, passing the message as an argument.
     */
    @JvmStatic
    fun Message.addButton(label: String, style: ButtonStyle, onPress: (Message, FragmentManager) -> Unit) {
        val id = (-CommandsAPI.generateId()).toString()
        val components = this.components ?: ArrayList<Component>().also { components -> 
            msgComponentsField[this] = components
        }

        try{
            val buttonComponent = ReflectUtils.allocateInstance(ButtonComponent::class.java) as ButtonComponent

            labelField[buttonComponent] = label
            styleField[buttonComponent] = style
            disabledField[buttonComponent] = false
            typeField[buttonComponent] = ComponentType.BUTTON
            idField[buttonComponent] = "${-CommandsAPI.ALIUCORD_APP_ID}-${id}"

            val row = components.lastOrNull() ?: ReflectUtils.allocateInstance<ActionRowComponent>(ActionRowComponent::class.java).also { row ->
                arTypeField[row] = ComponentType.ACTION_ROW
                components.add(row)
            }

            val rowItems = componentsField[row] as ArrayList<Component>? ?: ArrayList<Component>().also { rowItems ->
                componentsField[row] = rowItems
            }

            rowItems.add(buttonComponent)
            ButtonsAPI.actions.put(id.toString(), onPress)

            StoreStream.`access$getDispatcher$p`(StoreStream.getPresences().stream).schedule { 
                StoreStream.`access$handleMessageUpdate`(StoreStream.getPresences().stream, synthesizeApiMessage())
            }
        } catch (t: Throwable) {
            Logger("ButtonsAPI").error("Failed to create button component", t)
        }
    }

}