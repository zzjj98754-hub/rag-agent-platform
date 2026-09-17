import { http } from './http'
import type {
  DocumentListResponse,
  DocumentUploadResponse,
} from './types'
import type { IndexTaskStatus, AsyncUploadResponse } from './types'

export async function listDocuments(): Promise<DocumentListResponse> {
  const { data } =
    await http.get<DocumentListResponse>('/admin/documents')
  return data
}

export async function uploadDocument(
  file: File,
): Promise<DocumentUploadResponse> {
  const form = new FormData()
  form.append('file', file)
  const { data } = await http.post<DocumentUploadResponse>(
    '/admin/documents/upload',
    form,
  )
  return data
}

export async function uploadDocumentAsync(file: File, idempotencyKey?: string): Promise<AsyncUploadResponse> {
  const form = new FormData()
  form.append('file', file)
  const { data } = await http.post<AsyncUploadResponse>('/admin/documents/upload/async', form, {
    headers: idempotencyKey ? { 'Idempotency-Key': idempotencyKey } : undefined,
  })
  return data
}

export async function getIndexTaskStatus(taskId: string): Promise<IndexTaskStatus> {
  const { data } = await http.get<IndexTaskStatus>(`/admin/documents/status/${taskId}`)
  return data
}

export async function retryIndexTask(taskId: string): Promise<void> {
  await http.post(`/admin/documents/tasks/${taskId}/retry`)
}

export async function deleteDocument(documentId: number) {
  await http.delete(`/admin/documents/${documentId}`)
}
