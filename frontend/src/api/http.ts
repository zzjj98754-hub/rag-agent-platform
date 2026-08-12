import axios from 'axios'

export const TOKEN_STORAGE_KEY = 'rag_console_token'
export const USER_STORAGE_KEY = 'rag_console_user'
export const EXPIRES_STORAGE_KEY = 'rag_console_expires_at'

export const API_BASE_URL =
  import.meta.env.VITE_API_BASE_URL?.replace(/\/$/, '') || '/api'

export const http = axios.create({
  baseURL: API_BASE_URL,
  timeout: 30_000,
})

http.interceptors.request.use((config) => {
  const token = localStorage.getItem(TOKEN_STORAGE_KEY)
  if (token) {
    config.headers.Authorization = `Bearer ${token}`
  }
  return config
})

http.interceptors.response.use(
  (response) => response,
  (error) => {
    if (
      error.response?.status === 401 &&
      window.location.pathname !== '/login'
    ) {
      localStorage.removeItem(TOKEN_STORAGE_KEY)
      localStorage.removeItem(USER_STORAGE_KEY)
      localStorage.removeItem(EXPIRES_STORAGE_KEY)
      window.location.assign('/login')
    }
    return Promise.reject(error)
  },
)
