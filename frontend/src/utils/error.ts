import axios from 'axios'
import type { ApiError } from '../api/types'

export function errorMessage(
  error: unknown,
  fallback: string,
) {
  if (axios.isAxiosError<ApiError>(error)) {
    return error.response?.data?.message || error.message || fallback
  }
  return error instanceof Error ? error.message : fallback
}
