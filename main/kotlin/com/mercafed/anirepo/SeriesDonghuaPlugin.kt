package com.mercafed.anirepo

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

// La anotación @CloudstreamPlugin le dice a Gradle y a CloudStream que esta es la clase
// principal que contiene la lógica para cargar los proveedores.
@CloudstreamPlugin
class SeriesDonghuaPlugin: Plugin() {
    override fun load(context: Context) {
        // Registro de los proveedores que contiene este plugin.
        // Aquí registramos la clase que creaste.
        registerMainAPI(SeriesDonghuaProvider())
    }
}