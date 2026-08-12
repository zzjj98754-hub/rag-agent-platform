import { http } from './http'
import type { LoginResponse } from './types'

export async function login(
  username: string,
  password: string,
): Promise<LoginResponse> {
  const { data } = await http.post<LoginResponse>('/auth/login', {
    username,
    password,
  })
  return data
}
