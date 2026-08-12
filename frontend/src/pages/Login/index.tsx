import {
  ArrowRightOutlined,
  CheckCircleFilled,
  LockOutlined,
  SafetyCertificateOutlined,
  UserOutlined,
} from '@ant-design/icons'
import { Alert, App, Button, Form, Input } from 'antd'
import axios from 'axios'
import { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import type { ApiError } from '../../api/types'
import { useAuth } from '../../store/AuthContext'

interface LoginValues {
  username: string
  password: string
}

export default function LoginPage() {
  const { message } = App.useApp()
  const navigate = useNavigate()
  const { login } = useAuth()
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState('')

  const submit = async (values: LoginValues) => {
    setLoading(true)
    setError('')
    try {
      await login(values.username, values.password)
      message.success('登录成功')
      navigate('/chat', { replace: true })
    } catch (requestError) {
      const apiError = axios.isAxiosError<ApiError>(requestError)
        ? requestError.response?.data
        : undefined
      setError(apiError?.message || '登录失败，请检查服务和凭据')
    } finally {
      setLoading(false)
    }
  }

  return (
    <main className="login-page">
      <section className="login-story">
        <div className="login-brand">
          <span>R</span>
          RAG Intelligence
        </div>
        <div className="login-story__content">
          <span className="eyebrow eyebrow--light">
            Enterprise Knowledge Agent
          </span>
          <h1>
            让每一次回答
            <br />
            都有知识依据
          </h1>
          <p>
            自研 BM25 与向量检索协同召回，
            通过 RRF 融合和 BGE 精排提供可靠上下文。
          </p>
          <div className="capability-list">
            {[
              '真实 SSE 流式回答',
              '引用来源与检索链路可解释',
              'Agent Function Calling 执行轨迹',
            ].map((item) => (
              <div key={item}>
                <CheckCircleFilled />
                {item}
              </div>
            ))}
          </div>
        </div>
        <div className="login-story__foot">
          Spring Boot 3.3 · Java 17 · React 18
        </div>
      </section>

      <section className="login-form-panel">
        <div className="login-form-card">
          <div className="security-mark">
            <SafetyCertificateOutlined />
          </div>
          <span className="eyebrow">Secure workspace</span>
          <h2>登录知识库工作台</h2>
          <p className="login-intro">
            使用后端已创建的 JWT 用户账号登录
          </p>
          {error && (
            <Alert
              type="error"
              showIcon
              message={error}
              className="login-alert"
            />
          )}
          <Form<LoginValues>
            layout="vertical"
            requiredMark={false}
            onFinish={submit}
          >
            <Form.Item
              label="用户名"
              name="username"
              rules={[{ required: true, message: '请输入用户名' }]}
            >
              <Input
                size="large"
                prefix={<UserOutlined />}
                placeholder="请输入用户名"
                autoComplete="username"
              />
            </Form.Item>
            <Form.Item
              label="密码"
              name="password"
              rules={[{ required: true, message: '请输入密码' }]}
            >
              <Input.Password
                size="large"
                prefix={<LockOutlined />}
                placeholder="请输入密码"
                autoComplete="current-password"
              />
            </Form.Item>
            <Button
              block
              size="large"
              type="primary"
              htmlType="submit"
              loading={loading}
              iconPosition="end"
              icon={<ArrowRightOutlined />}
            >
              进入工作台
            </Button>
          </Form>
          <div className="login-security-note">
            <LockOutlined />
            JWT 身份验证 · RBAC 权限控制
          </div>
        </div>
      </section>
    </main>
  )
}
