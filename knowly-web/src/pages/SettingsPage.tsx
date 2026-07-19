import { useState, useEffect } from 'react'
import { getSettings, updateSettings, updatePassword } from '../services/api'

export default function SettingsPage() {
  const [settings, setSettings] = useState<Record<string, string>>({})
  const [newApiKey, setNewApiKey] = useState('')
  const [newPassword, setNewPassword] = useState('')
  const [message, setMessage] = useState('')

  useEffect(() => {
    loadSettings()
  }, [])

  const loadSettings = async () => {
    try {
      const data = await getSettings()
      setSettings(data || {})
    } catch (err: any) {
      setMessage('加载设置失败: ' + err.message)
    }
  }

  const handleSaveApiKey = async () => {
    if (!newApiKey.trim()) return
    try {
      await updateSettings({ dashscope_api_key: newApiKey })
      setNewApiKey('')
      setMessage('API Key 已保存')
      loadSettings()
    } catch (err: any) {
      setMessage('保存失败: ' + err.message)
    }
  }

  const handleUpdatePassword = async () => {
    if (newPassword.length < 6) {
      setMessage('密码至少 6 位')
      return
    }
    try {
      await updatePassword(newPassword)
      setNewPassword('')
      setMessage('密码已修改')
    } catch (err: any) {
      setMessage('修改失败: ' + err.message)
    }
  }

  return (
    <div style={{ maxWidth: 600, margin: '0 auto', padding: 24 }}>
      <h2 style={{ marginBottom: 24 }}>设置</h2>

      {message && <div style={{ padding: '8px 12px', background: '#e6f7ff', borderRadius: 4, marginBottom: 16, fontSize: 14 }}>{message}</div>}

      {/* API Key 配置 */}
      <div style={{ marginBottom: 32, padding: 16, border: '1px solid #eee', borderRadius: 4 }}>
        <h3 style={{ fontSize: 16, marginBottom: 12 }}>DashScope API Key</h3>
        <p style={{ color: '#999', fontSize: 13, marginBottom: 8 }}>
          当前: {settings.dashscope_api_key || '未配置'}
        </p>
        <div style={{ display: 'flex', gap: 8 }}>
          <input
            type="password"
            placeholder="输入新的 API Key"
            value={newApiKey}
            onChange={(e) => setNewApiKey(e.target.value)}
            style={{ flex: 1, padding: '8px 12px', border: '1px solid #ddd', borderRadius: 4, fontSize: 14 }}
          />
          <button onClick={handleSaveApiKey} style={{ padding: '8px 16px', background: '#1890ff', color: '#fff', border: 'none', borderRadius: 4, cursor: 'pointer' }}>
            保存
          </button>
        </div>
      </div>

      {/* 修改密码 */}
      <div style={{ marginBottom: 32, padding: 16, border: '1px solid #eee', borderRadius: 4 }}>
        <h3 style={{ fontSize: 16, marginBottom: 12 }}>修改密码</h3>
        <div style={{ display: 'flex', gap: 8 }}>
          <input
            type="password"
            placeholder="输入新密码（至少 6 位）"
            value={newPassword}
            onChange={(e) => setNewPassword(e.target.value)}
            style={{ flex: 1, padding: '8px 12px', border: '1px solid #ddd', borderRadius: 4, fontSize: 14 }}
          />
          <button onClick={handleUpdatePassword} style={{ padding: '8px 16px', background: '#1890ff', color: '#fff', border: 'none', borderRadius: 4, cursor: 'pointer' }}>
            修改
          </button>
        </div>
      </div>
    </div>
  )
}
