import { http } from './http'
import type {
  DocumentListResponse,
  DocumentUploadResponse,
} from './types'

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

export async function deleteDocument(documentId: number) {
  await http.delete(`/admin/documents/${documentId}`)
}
