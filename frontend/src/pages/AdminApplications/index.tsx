import { EditOutlined, ReloadOutlined, SettingOutlined } from '@ant-design/icons'
import { Alert, App, Button, Card, Empty, Input, Modal, Select, Space, Spin, Table, Tag } from 'antd'
import { useEffect, useState } from 'react'
import { initializePrinterDemo, listAllApplications, updateApplicationStatus } from '../../api/printer'
import type { AfterSalesApplication } from '../../api/types'

const labels = { PENDING: '待处理', PROCESSING: '处理中', COMPLETED: '已完成' }

export default function AdminApplicationsPage() {
  const { message } = App.useApp()
  const [items, setItems] = useState<AfterSalesApplication[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  const [initing, setIniting] = useState(false)
  const [editing, setEditing] = useState<AfterSalesApplication>()
  const [status, setStatus] = useState<AfterSalesApplication['status']>('PROCESSING')
  const [note, setNote] = useState('')
  const load = () => { setLoading(true); listAllApplications().then(setItems).catch(() => setError('申请加载失败')).finally(() => setLoading(false)) }
  useEffect(load, [])
  const init = async () => { setIniting(true); try { const result = await initializePrinterDemo(); message.success(`已初始化 ${result.products} 个型号、${result.documents} 份资料`); window.location.reload() } catch { message.error('初始化失败，请检查数据库连接') } finally { setIniting(false) } }
  const save = async () => { if (!editing) return; try { await updateApplicationStatus(editing.id, status, note); message.success('状态已更新'); setEditing(undefined); load() } catch { message.error('状态更新失败') } }
  return <div className="page"><header className="page-header"><div><span className="eyebrow">Admin Desk</span><h1>售后管理处理</h1><p>管理员可初始化演示资料并更新申请状态，普通用户无此入口。</p></div><Space><Button icon={<SettingOutlined />} loading={initing} onClick={init}>初始化演示资料</Button><Button icon={<ReloadOutlined />} onClick={load}>刷新</Button></Space></header>
    {error && <Alert type="error" message={error} />}
    <Card>{loading ? <Spin tip="正在加载申请..." /> : items.length === 0 ? <Empty description="暂无售后申请" /> : <Table rowKey="id" dataSource={items} columns={[{ title: '申请编号', dataIndex: 'applicationNo' }, { title: '用户', dataIndex: 'username' }, { title: '型号', dataIndex: 'modelName' }, { title: '问题', dataIndex: 'question', ellipsis: true }, { title: '状态', dataIndex: 'status', render: (value: AfterSalesApplication['status']) => <Tag color={value === 'COMPLETED' ? 'green' : value === 'PROCESSING' ? 'blue' : 'gold'}>{labels[value]}</Tag> }, { title: '操作', render: (_: unknown, record: AfterSalesApplication) => <Button icon={<EditOutlined />} onClick={() => { setEditing(record); setStatus(record.status === 'COMPLETED' ? 'COMPLETED' : 'PROCESSING'); setNote(record.processingNote || '') }}>处理</Button> }]} />}</Card>
    <Modal open={Boolean(editing)} title={editing ? `处理 ${editing.applicationNo}` : ''} onCancel={() => setEditing(undefined)} onOk={save} okText="保存"><p>{editing?.modelName} · {editing?.username}</p><Select value={status} onChange={setStatus} style={{ width: '100%', marginBottom: 12 }} options={Object.entries(labels).map(([value, label]) => ({ value, label }))} /><Input.TextArea value={note} onChange={(event) => setNote(event.target.value)} placeholder="处理说明，例如：已完成远程复核，请按说明更换纸张" maxLength={2000} autoSize={{ minRows: 4 }} /></Modal>
  </div>
}
