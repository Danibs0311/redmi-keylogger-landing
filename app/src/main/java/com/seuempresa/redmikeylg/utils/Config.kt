package com.seuempresa.redmikeylg.utils

/**
 * Constantes de configuração para integração com o Supabase e autenticação da telemetria.
 */
object Config {
    /**
     * URL do projeto Supabase.
     */
    const val SUPABASE_URL = "https://bsxwnkmztxcfjgqflysf.supabase.co"

    /**
     * Chave pública / Anon Key do projeto Supabase.
     */
    const val SUPABASE_ANON_KEY = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6ImJzeHdua216dHhjZmpncWZseXNmIiwicm9sZSI6ImFub24iLCJpYXQiOjE3OTMwMzA0MzMsImV4cCI6MjEwODYwNjQzM30.N-DjY4xvXyeBtYIikwIq1CMGu9zSvsZYLa5S4xNWKpw"

    /**
     * Chave secreta de autenticação para a telemetria / API Key corporativa.
     */
    const val API_KEY_PLACEHOLDER = "SUA_CHAVE_SECRETA_AQUI"
}
