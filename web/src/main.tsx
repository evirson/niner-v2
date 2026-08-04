import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { BrowserRouter } from 'react-router-dom'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import App from './App.tsx'
import { AuthProvider } from './lib/auth'
import { consumeHandoffToken } from './lib/api'
import { iniciarNavegacaoGlobalPorEnter } from './lib/formularios'
import './styles.css'

// Recebe o token vindo do site (#token=...) no primeiro acesso pós-signup.
consumeHandoffToken()

// Enter muda de campo (igual Tab) em qualquer <input> solto fora de <form> — telas de cadastro
// têm o próprio mecanismo via `aoTeclarEnterNoFormulario` (ver formularios.ts).
iniciarNavegacaoGlobalPorEnter()

const queryClient = new QueryClient()

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <QueryClientProvider client={queryClient}>
      <AuthProvider>
        <BrowserRouter>
          <App />
        </BrowserRouter>
      </AuthProvider>
    </QueryClientProvider>
  </StrictMode>,
)
