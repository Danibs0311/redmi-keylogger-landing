import React from 'react'
import LogTable from './components/LogTable'
import { ShieldAlert, Terminal } from 'lucide-react'

export default function App() {
  return (
    <div className="min-h-screen bg-[#09090b] text-zinc-100 flex flex-col">
      {/* Top Navbar */}
      <header className="border-b border-zinc-800 bg-zinc-950/80 backdrop-blur-md sticky top-0 z-50">
        <div className="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8 h-16 flex items-center justify-between">
          <div className="flex items-center gap-3">
            <div className="w-9 h-9 rounded-lg bg-gradient-to-br from-purple-600 to-indigo-600 flex items-center justify-center shadow-lg shadow-purple-500/20">
              <Terminal className="w-5 h-5 text-white" />
            </div>
            <div>
              <h1 className="text-base font-bold tracking-tight text-white flex items-center gap-2">
                RedmiKeylogger
                <span className="text-[10px] uppercase font-mono px-2 py-0.5 rounded bg-purple-500/10 text-purple-400 border border-purple-500/20">
                  Supabase Live
                </span>
              </h1>
              <p className="text-xs text-zinc-500">Console de Auditoria e Telemetria</p>
            </div>
          </div>

          <div className="hidden sm:flex items-center gap-2 text-xs text-zinc-500 font-mono">
            <span>postgres_changes</span>
            <span>•</span>
            <span className="text-zinc-400">table: keylogs</span>
          </div>
        </div>
      </header>

      {/* Main Content Area */}
      <main className="flex-1 max-w-7xl w-full mx-auto px-4 sm:px-6 lg:px-8 py-8">
        <LogTable />
      </main>

      {/* Footer */}
      <footer className="border-t border-zinc-900 py-6 text-center text-xs text-zinc-600 font-mono">
        RedmiKeylogger Real-Time Dashboard • Construído com React, Vite, Tailwind CSS e Supabase
      </footer>
    </div>
  )
}
