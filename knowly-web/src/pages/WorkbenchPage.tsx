import { useState, useEffect, useRef } from 'react'
import {
  browseFiles, createJob, getCurrentJob, getUnfinished,
  previewChunks as apiPreviewChunks, subscribeProgress, getOutputMarkdown,
  type FileEntry, type ChunkPreview, type JobStatus
} from '../services/api'

export default function WorkbenchPage() {
  // ── 选文件 ──
  const [currentPath, setCurrentPath] = useState(localStorage.getItem('knowly_lastPath') || '/data')
  const [entries, setEntries] = useState<FileEntry[]>([])
  const [selectedPath, setSelectedPath] = useState('')
  const [manualPath, setManualPath] = useState('')

  // ── 清洗参数 ──
  const [maxSize, setMaxSize] = useState(500)
  const [overlap, setOverlap] = useState(50)
  const [outputPath, setOutputPath] = useState('')  // 从后端获取默认值

  // ── sink 多选 ──
  const [sinks, setSinks] = useState<string[]>(['markdown', 'jsonl'])
  const allSinks = [
    { id: 'markdown', label: 'Markdown（人类可读）', needsEmbed: false },
    { id: 'jsonl', label: 'JSONL（结构化）', needsEmbed: false },
    { id: 'qdrant', label: 'Qdrant 向量库', needsEmbed: true },
    { id: 'pgvector', label: 'PgVector 向量库', needsEmbed: true },
  ]

  const toggleSink = (id: string) => {
    setSinks(prev => prev.includes(id) ? prev.filter(s => s !== id) : [...prev, id])
  }

  // ── 分段预览 ──
  const [previewChunks, setPreviewChunks] = useState<ChunkPreview[]>([])
  const [previewTotal, setPreviewTotal] = useState(0)
  const [previewLoading, setPreviewLoading] = useState(false)

  // ── 任务状态 ──
  const [jobStatus, setJobStatus] = useState<JobStatus | null>(null)
  const [progressLogs, setProgressLogs] = useState<string[]>([])
  const [isRunning, setIsRunning] = useState(false)
  const eventSourceRef = useRef<EventSource | null>(null)

  useEffect(() => {
    browse(currentPath)
    checkUnfinished()
    // 从后端获取默认输出目录（用带 token 的请求）
    fetch('/api/settings/default-output', {
      headers: { 'Authorization': `Bearer ${localStorage.getItem('knowly_token')}` }
    })
      .then(r => r.json())
      .then(d => { if (d.code === 0 && d.data) setOutputPath(d.data) })
      .catch(() => setOutputPath('/data/knowly/knowly-output'))
    return () => { eventSourceRef.current?.close() }
  }, [])

  // ── 文件浏览 ──
  const browse = async (path: string) => {
    try {
      const data = await browseFiles(path)
      setCurrentPath(data.path)
      setEntries(data.entries || [])
      localStorage.setItem('knowly_lastPath', data.path)
    } catch (err: any) {
      setProgressLogs(prev => [...prev, `浏览失败: ${err.message}`])
    }
  }

  // 手动输入路径确认
  const handleManualSelect = () => {
    if (manualPath.trim()) {
      const path = manualPath.trim()
      setSelectedPath(path)
      // 如果是目录，同时切换目录浏览器到该路径
      browse(path)
      setManualPath('')
    }
  }

  const selectFile = (entry: FileEntry) => {
    // 文件和目录都能选中
    setSelectedPath(entry.path)
  }

  const enterDir = (entry: FileEntry) => {
    if (entry.isDirectory) {
      browse(entry.path)
    }
  }

  // ── 分段预览（Web 核心价值，仅支持单个文件）──
  const selectedIsDir = entries.some(e => e.path === selectedPath && e.isDirectory) || !selectedPath

  const handlePreview = async () => {
    if (!selectedPath) {
      alert('请先选择一个文件')
      return
    }
    if (selectedIsDir) {
      alert('分段预览只支持单个文件，不支持目录。请选择一个文件后再预览。')
      return
    }
    setPreviewLoading(true)
    try {
      const result = await apiPreviewChunks({
        sourcePath: selectedPath,
        chunking: { maxSize, overlap }
      })
      setPreviewChunks(result.previewChunks)
      setPreviewTotal(result.totalChunks)
    } catch (err: any) {
      alert('预览失败: ' + err.message)
    } finally {
      setPreviewLoading(false)
    }
  }

  // ── 开始清洗 ──
  const handleStart = async () => {
    if (!selectedPath) {
      alert('请先选择输入文件或目录')
      return
    }
    setProgressLogs([])
    setIsRunning(true)
    setJobStatus(null)  // 清除上次结果显示
    try {
      // 先建立 SSE 连接，再创建任务，避免漏掉前几个事件
      subscribeProgressEvents()
      await createJob(selectedPath, outputPath, sinks)
    } catch (err: any) {
      alert('启动失败: ' + err.message)
      setIsRunning(false)
    }
  }

  // ── SSE 进度订阅 ──
  const subscribeProgressEvents = () => {
    const es = subscribeProgress((eventName, data) => {
      if (eventName === 'PipelineStarted') {
        setProgressLogs(prev => [...prev, `🚀 开始清洗...`])
      } else if (eventName === 'FileStarted') {
        setProgressLogs(prev => [...prev, `📄 处理中: ${data}`])
      } else if (eventName === 'FileCompleted') {
        setProgressLogs(prev => [...prev, `✅ ${data}`])
      } else if (eventName === 'FileFailed') {
        setProgressLogs(prev => [...prev, `❌ ${data}`])
      } else if (eventName === 'PipelineFinished') {
        setProgressLogs(prev => [...prev, `🎉 清洗完成！`])
        setIsRunning(false)
        loadCurrentJob()
      }
    })
    eventSourceRef.current = es
  }

  // ── 检测未完成任务（断点续跑提示）──
  const checkUnfinished = async () => {
    try {
      const has = await getUnfinished()
      if (has) {
        setProgressLogs(prev => [...prev, '⚠️ 上次清洗未完成，可重新启动继续'])
      }
    } catch (ignored) {}
  }

  const loadCurrentJob = async () => {
    try {
      const job = await getCurrentJob()
      setJobStatus(job)
      if (job?.status === 'RUNNING') setIsRunning(true)
    } catch (ignored) {}
  }

  // ── 渲染 ──
  return (
    <div style={{ maxWidth: 1200, margin: '0 auto', padding: 24 }}>
      <h2 style={{ marginBottom: 16 }}>🧹 清洗工作台</h2>

      <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 24 }}>
        {/* 左栏：选文件 + 配置 */}
        <div>
          {/* 选文件 */}
          <div style={{ marginBottom: 16, padding: 16, border: '1px solid #eee', borderRadius: 4 }}>
            <h3 style={{ fontSize: 15, marginBottom: 8 }}>选择文件/目录</h3>

            {/* 手动输入路径 */}
            <div style={{ display: 'flex', gap: 8, marginBottom: 12 }}>
              <input
                type="text"
                placeholder="直接输入文件或目录路径..."
                value={manualPath}
                onChange={e => setManualPath(e.target.value)}
                onKeyDown={e => e.key === 'Enter' && handleManualSelect()}
                style={{ flex: 1, padding: '6px 10px', border: '1px solid #ddd', borderRadius: 4, fontSize: 13 }}
              />
              <button onClick={handleManualSelect}
                style={{ padding: '6px 12px', background: '#1890ff', color: '#fff', border: 'none', borderRadius: 4, cursor: 'pointer', fontSize: 13 }}>
                确认
              </button>
            </div>

            {/* 目录浏览器 */}
            <div style={{ marginBottom: 8, fontSize: 13, color: '#666' }}>浏览: {currentPath}</div>
            <div style={{ maxHeight: 200, overflowY: 'auto', border: '1px solid #eee', borderRadius: 4 }}>
              {entries.length === 0 && <div style={{ padding: 12, color: '#999' }}>空目录</div>}
              {currentPath !== '/' && (
                <div onClick={() => browse(currentPath.substring(0, currentPath.lastIndexOf('/')) || '/')}
                  style={{ padding: '6px 12px', cursor: 'pointer', color: '#1890ff', borderBottom: '1px solid #f0f0f0' }}>
                  📁 ..
                </div>
              )}
              {entries.map((entry, i) => (
                <div key={i}
                  style={{
                    display: 'flex', alignItems: 'center', justifyContent: 'space-between',
                    padding: '6px 12px', borderBottom: '1px solid #f0f0f0',
                    background: selectedPath === entry.path ? '#e6f7ff' : 'transparent',
                  }}>
                  <div onClick={() => selectFile(entry)}
                    style={{ flex: 1, cursor: 'pointer' }}>
                    {entry.isDirectory ? '📁' : '📄'} {entry.name}
                  </div>
                  {entry.isDirectory && (
                    <button onClick={() => enterDir(entry)}
                      style={{ padding: '2px 8px', background: 'transparent', border: '1px solid #1890ff', color: '#1890ff', borderRadius: 3, cursor: 'pointer', fontSize: 12 }}>
                      进入
                    </button>
                  )}
                </div>
              ))}
            </div>
            {selectedPath && (
              <div style={{ marginTop: 8, fontSize: 13, color: '#1890ff' }}>
                已选: {selectedPath}
              </div>
            )}
          </div>

          {/* 清洗参数 */}
          <div style={{ marginBottom: 16, padding: 16, border: '1px solid #eee', borderRadius: 4 }}>
            <h3 style={{ fontSize: 15, marginBottom: 8 }}>清洗参数</h3>
            <label style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 8, fontSize: 14 }}>
              最大分段: <input type="number" value={maxSize} onChange={e => setMaxSize(+e.target.value)} style={{ width: 80, padding: '4px 8px', border: '1px solid #ddd', borderRadius: 4 }} /> 字符
            </label>
            <label style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 8, fontSize: 14 }}>
              重叠长度: <input type="number" value={overlap} onChange={e => setOverlap(+e.target.value)} style={{ width: 80, padding: '4px 8px', border: '1px solid #ddd', borderRadius: 4 }} /> 字符
            </label>
            <label style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 12, fontSize: 14 }}>
              输出目录: <input type="text" value={outputPath} onChange={e => setOutputPath(e.target.value)} style={{ flex: 1, padding: '4px 8px', border: '1px solid #ddd', borderRadius: 4 }} />
            </label>

            {/* Sink 多选 */}
            <div style={{ borderTop: '1px solid #f0f0f0', paddingTop: 8 }}>
              <div style={{ fontSize: 14, marginBottom: 6, color: '#666' }}>产出目标（可多选）:</div>
              {allSinks.map(s => (
                <label key={s.id} style={{ display: 'flex', alignItems: 'center', gap: 6, marginBottom: 4, fontSize: 13, cursor: 'pointer' }}>
                  <input
                    type="checkbox"
                    checked={sinks.includes(s.id)}
                    onChange={() => toggleSink(s.id)}
                  />
                  {s.label}
                  {s.needsEmbed && <span style={{ color: '#faad14', fontSize: 11 }}>(需 Embedding API)</span>}
                </label>
              ))}
            </div>
          </div>

          {/* 操作按钮 */}
          <div style={{ display: 'flex', gap: 8 }}>
            <button onClick={handlePreview} disabled={previewLoading || isRunning || selectedIsDir}
              style={{ padding: '8px 16px', background: selectedIsDir ? '#ccc' : '#52c41a', color: '#fff', border: 'none', borderRadius: 4, cursor: selectedIsDir ? 'not-allowed' : 'pointer' }}>
              {previewLoading ? '预览中...' : selectedIsDir ? '🔍 预览（选文件后可用）' : '🔍 分段预览'}
            </button>
            <button onClick={handleStart} disabled={isRunning}
              style={{ padding: '8px 16px', background: '#1890ff', color: '#fff', border: 'none', borderRadius: 4, cursor: 'pointer' }}>
              {isRunning ? '清洗中...' : '🚀 开始清洗'}
            </button>
          </div>
        </div>

        {/* 右栏：预览 + 进度 */}
        <div>
          {/* 分段预览结果 */}
          {previewChunks.length > 0 && (
            <div style={{ marginBottom: 16, padding: 16, border: '1px solid #eee', borderRadius: 4 }}>
              <h3 style={{ fontSize: 15, marginBottom: 8 }}>
                分段预览 <span style={{ color: '#999', fontSize: 13 }}>(共 {previewTotal} 个，显示前 {previewChunks.length})</span>
              </h3>
              <div style={{ maxHeight: 300, overflowY: 'auto' }}>
                {previewChunks.map((chunk, i) => (
                  <div key={i} style={{ padding: 8, marginBottom: 8, background: '#fafafa', borderRadius: 4, fontSize: 13 }}>
                    <div style={{ color: '#999', marginBottom: 4 }}>#{chunk.ordinal} · {chunk.charCount} 字 · {chunk.sectionTitle || '无章节'}</div>
                    <div style={{ whiteSpace: 'pre-wrap', wordBreak: 'break-all' }}>
                      {chunk.text.substring(0, 200)}{chunk.text.length > 200 ? '...' : ''}
                    </div>
                  </div>
                ))}
              </div>
            </div>
          )}

          {/* 实时进度 */}
          {(isRunning || progressLogs.length > 0) && (
            <div style={{ padding: 16, border: '1px solid #eee', borderRadius: 4 }}>
              <h3 style={{ fontSize: 15, marginBottom: 8 }}>进度</h3>
              <div style={{ maxHeight: 300, overflowY: 'auto', fontSize: 13, fontFamily: 'monospace' }}>
                {progressLogs.map((log, i) => (
                  <div key={i} style={{ padding: '2px 0' }}>{log}</div>
                ))}
              </div>
            </div>
          )}

          {/* 任务结果统计 */}
          {jobStatus?.status === 'SUCCESS' && jobStatus.stats && (
            <div style={{ marginTop: 16, padding: 16, background: '#f6ffed', border: '1px solid #b7eb8f', borderRadius: 4 }}>
              <h3 style={{ fontSize: 15, marginBottom: 8 }}>✅ 清洗完成</h3>
              <div style={{ fontSize: 14 }}>
                <div>总文件: {jobStatus.stats.totalFiles}</div>
                <div>成功: {jobStatus.stats.succeeded}</div>
                <div>失败: {jobStatus.stats.failed}</div>
                <div>总 chunk: {jobStatus.stats.totalChunks}</div>
                <div>产出: {outputPath}</div>
              </div>
            </div>
          )}
        </div>
      </div>
    </div>
  )
}
