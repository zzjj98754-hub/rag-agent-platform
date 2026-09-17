import { http } from './http'
import type {
  AfterSalesApplication,
  PrinterProduct,
  PrinterQaResponse,
} from './types'

export async function listPrinterProducts() {
  const { data } = await http.get<PrinterProduct[]>('/printer-assistant/products')
  return data
}

export async function askPrinter(productId: number, question: string) {
  const { data } = await http.post<PrinterQaResponse>('/printer-assistant/qa', {
    productId,
    question,
  })
  return data
}

export async function createAfterSales(
  payload: {
    productId: number
    question: string
    troubleshootingSteps: string
    additionalNote?: string
  },
  idempotencyKey: string,
) {
  const { data } = await http.post<AfterSalesApplication>(
    '/printer-assistant/applications',
    payload,
    { headers: { 'Idempotency-Key': idempotencyKey } },
  )
  return data
}

export async function listMyApplications() {
  const { data } = await http.get<AfterSalesApplication[]>('/printer-assistant/applications')
  return data
}

export async function getApplication(id: string | number) {
  const { data } = await http.get<AfterSalesApplication>(`/printer-assistant/applications/${id}`)
  return data
}

export async function initializePrinterDemo() {
  const { data } = await http.post<{ products: number; documents: number }>(
    '/printer-assistant/admin/initialize',
  )
  return data
}

export async function listAllApplications() {
  const { data } = await http.get<AfterSalesApplication[]>('/printer-assistant/admin/applications')
  return data
}

export async function updateApplicationStatus(
  id: number,
  status: AfterSalesApplication['status'],
  processingNote: string,
) {
  const { data } = await http.put<AfterSalesApplication>(
    `/printer-assistant/admin/applications/${id}/status`,
    { status, processingNote },
  )
  return data
}
