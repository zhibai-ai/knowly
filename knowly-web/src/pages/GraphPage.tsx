import { useState } from 'react'
import { buildGraph, getGraphStatus } from '../services/api'

export default function GraphPage() {
  const [jsonlPath, setJsonlPath] = useState('/data/archive/output/nihaixia/01-chunks/chunks.jsonl')
  const [isBuilding, setIsBuilding] = useState(false)
  const [status, setStatus] = useState<any>(null)
  const [message, setMessage] = useState('')

  const handleBuild = async () => {
    if (!jsonlPath.trim()) {
      alert('请输入 JSONL 文件路径')
      return
    }
    setMessage('图谱构建已启动，请等待...')
    setIsBuilding(true)
    try {
      await buildGraph(jsonlPath)
      // 轮询状态
      const poll = setInterval(async () => {
        try {
          const s = await getGraphStatus()
          setStatus(s)
          if (s && s.status !== 'RUNNING') {
            clearInterval(poll)
            setIsBuilding(false)
            if (s.status === 'SUCCESS') {
              setMessage(`构建完成：${s.totalEntities} 实体，${s.totalRelations} 关系`)
            }
          }
        } catch (ignored) {}
      }, 5000)
    } catch (err: any) {
      setMessage('启动失败: ' + err.message)
      setIsBuilding(false)
    }
  }

  return (
    <div style={{ maxWidth: 800, margin: '0 auto', padding: 24 }}>
      <h2 style={{ marginBottom: 16 }}>🔗 知识图谱构建</h2>

      <div style={{ marginBottom: 16, padding: 16, border: '1px solid #eee', borderRadius: 4 }}>
        <p style={{ fontSize: 14, color: '#666', marginBottom: 12 }}>
          从清洗产出的 JSONL 构建知识图谱（实体 + 关系 → Neo4j）。
          <br/>每个 chunk 调用通义千问 LLM 抽取实体和关系，约 10 秒/chunk。
          内容审核拦截的 chunk 会自动跳过（国学/命理文本可能有一定比例被拦）。
        </p>

        <label style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 12, fontSize: 14 }}>
          JSONL 路径:
          <input
            type="text"
            value={jsonlPath}
            onChange={e => setJsonlPath(e.target.value)}
            style={{ flex: 1, padding: '6px 10px', border: '1px solid #ddd', borderRadius: 4, fontSize: 13 }}
          />
        </label>

        <button
          onClick={handleBuild}
          disabled={isBuilding}
          style={{
            padding: '8px 24px',
            background: isBuilding ? '#ccc' : '#722ed1',
            color: '#fff',
            border: 'none',
            borderRadius: 4,
            cursor: isBuilding ? 'not-allowed' : 'pointer',
            fontSize: 14,
          }}
        >
          {isBuilding ? '构建中...' : '🚀 开始构建图谱'}
        </button>
      </div>

      {message && (
        <div style={{ padding: '8px 12px', background: '#f6ffed', border: '1px solid #b7eb8f', borderRadius: 4, marginBottom: 16, fontSize: 14 }}>
          {message}
        </div>
      )}

      {status && (
        <div style={{ padding: 16, border: '1px solid #eee', borderRadius: 4 }}>
          <h3 style={{ fontSize: 15, marginBottom: 12 }}>📊 构建状态</h3>
          <div style={{ fontSize: 14, lineHeight: 1.8 }}>
            <div>状态: <strong>{status.status === 'RUNNING' ? '🔄 构建中' : '✅ 完成'}</strong></div>
            {status.status === 'RUNNING' ? (
              <>
                <div>已处理 chunk: {status.processedChunks || 0} / {status.totalChunks || '?'}</div>
                {status.totalChunks > 0 && (
                  <div style={{ fontSize: 12, color: '#999' }}>
                    进度: {((status.processedChunks / status.totalChunks) * 100).toFixed(1)}%
                  </div>
                )}
                <div>已写入实体: {status.entities || 0}</div>
                <div>已写入关系: {status.relations || 0}</div>
                {status.filteredByModeration > 0 && (
                  <div style={{ color: '#fa8c16' }}>
                    ⚠️ 内容审核拦截: {status.filteredByModeration} chunk
                  </div>
                )}
              </>
            ) : (
              <>
                <div>总 chunk: {status.totalChunks || 0}</div>
                <div>实体总数: {status.totalEntities || 0}</div>
                <div>关系总数: {status.totalRelations || 0}</div>
                <div>失败 chunk: {status.failedChunks || 0}</div>
                {status.filteredByModeration > 0 && (
                  <div style={{ color: '#fa8c16' }}>
                    ⚠️ 内容审核拦截: {status.filteredByModeration} chunk
                  </div>
                )}
              </>
            )}
          </div>
          {status.status === 'SUCCESS' && (
            <div style={{ marginTop: 12, padding: 8, background: '#e6f7ff', borderRadius: 4, fontSize: 13 }}>
              📌 查看图谱：<a href="http://localhost:7474" target="_blank" style={{ color: '#1890ff' }}>Neo4j Browser</a>
              （用户名 neo4j / 密码 knowly_dev）
              <br/>查询示例：<code>MATCH (n) RETURN n LIMIT 50</code>
            </div>
          )}
        </div>
      )}
    </div>
  )
}
