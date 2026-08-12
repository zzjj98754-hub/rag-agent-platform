import {
  createContext,
  useCallback,
  useContext,
  useMemo,
  useState,
  type PropsWithChildren,
} from 'react'
import { login as loginRequest } from '../api/auth'
import {
  EXPIRES_STORAGE_KEY,
  TOKEN_STORAGE_KEY,
  USER_STORAGE_KEY,
} from '../api/http'
import type { UserInfo } from '../api/types'

interface AuthContextValue {
  token: string | null
  user: UserInfo | null
  isAuthenticated: boolean
  login: (username: string, password: string) => Promise<void>
  logout: () => void
}

const AuthContext = createContext<AuthContextValue | null>(null)

function loadStoredAuth() {
  const token = localStorage.getItem(TOKEN_STORAGE_KEY)
  const expiresAt = localStorage.getItem(EXPIRES_STORAGE_KEY)
  const rawUser = localStorage.getItem(USER_STORAGE_KEY)
  if (
    !token ||
    !rawUser ||
    !expiresAt ||
    Date.parse(expiresAt) <= Date.now()
  ) {
    localStorage.removeItem(TOKEN_STORAGE_KEY)
    localStorage.removeItem(USER_STORAGE_KEY)
    localStorage.removeItem(EXPIRES_STORAGE_KEY)
    return { token: null, user: null }
  }
  try {
    return {
      token,
      user: JSON.parse(rawUser) as UserInfo,
    }
  } catch {
    return { token: null, user: null }
  }
}

export function AuthProvider({ children }: PropsWithChildren) {
  const stored = useMemo(loadStoredAuth, [])
  const [token, setToken] = useState<string | null>(stored.token)
  const [user, setUser] = useState<UserInfo | null>(stored.user)

  const login = useCallback(
    async (username: string, password: string) => {
      const result = await loginRequest(username, password)
      localStorage.setItem(
        TOKEN_STORAGE_KEY,
        result.accessToken,
      )
      localStorage.setItem(
        USER_STORAGE_KEY,
        JSON.stringify(result.user),
      )
      localStorage.setItem(
        EXPIRES_STORAGE_KEY,
        result.expiresAt,
      )
      setToken(result.accessToken)
      setUser(result.user)
    },
    [],
  )

  const logout = useCallback(() => {
    localStorage.removeItem(TOKEN_STORAGE_KEY)
    localStorage.removeItem(USER_STORAGE_KEY)
    localStorage.removeItem(EXPIRES_STORAGE_KEY)
    setToken(null)
    setUser(null)
  }, [])

  return (
    <AuthContext.Provider
      value={{
        token,
        user,
        isAuthenticated: Boolean(token && user),
        login,
        logout,
      }}
    >
      {children}
    </AuthContext.Provider>
  )
}

export function useAuth() {
  const value = useContext(AuthContext)
  if (!value) {
    throw new Error('useAuth 必须在 AuthProvider 内使用')
  }
  return value
}
