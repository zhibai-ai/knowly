// ── 类型定义 ──

export interface LoginRequest {
  username: string
  password: string
}

export interface LoginResponse {
  token: string
  username: string
}

export interface JobCreateRequest {
  sourcePath: string
  outputPath: string
}

export interface JobStatus {
  jobId: string
  status: string  // RUNNING / SUCCESS / FAILED
  stats?: {
    totalFiles: number
    succeeded: number
    failed: number
    totalChunks: number
  }
}

export interface ChunkPreview {
  id: string
  text: string
  ordinal: number
  charCount: number
  sectionTitle?: string
  sectionLevel?: number
}

export interface PreviewRequest {
  sourcePath: string
  chunking: {
    maxSize?: number
    overlap?: number
    minSize?: number
  }
}

export interface PreviewResponse {
  totalChunks: number
  previewChunks: ChunkPreview[]
  truncated: boolean
}

export interface FileEntry {
  name: string
  path: string
  isDirectory: boolean
  size: number
}

export interface Template {
  name: string
  builtin: boolean
}

export interface AppSettings {
  [key: string]: string
}

// ── API 请求封装 ──

const API_BASE = '/api'  // 通过 Vite proxy 转发到 localhost:8080

function getToken(): string | null {
  return localStorage.getItem('knowly_token')
}

async function request<T>(path: string, options?: RequestInit): Promise<T> {
  const token = getToken()
  const headers: Record<string, string> = {
    'Content-Type': 'application/json',
    ...(options?.headers as Record<string, string> || {}),
  }
  if (token) {
    headers['Authorization'] = `Bearer ${token}`
  }

  const resp = await fetch(`${API_BASE}${path}`, { ...options, headers })

  if (resp.status === 401) {
    localStorage.removeItem('knowly_token')
    window.location.href = '/login'
    throw new Error('未登录')
  }

  const data = await resp.json()
  if (data.code !== 0 && data.code !== undefined) {
    throw new Error(data.message || '请求失败')
  }
  return data.data as T
}

// ── 认证 ──

export async function login(username: string, password: string): Promise<LoginResponse> {
  const data = await request<{ token: string; username: string }>('/auth/login', {
    method: 'POST',
    body: JSON.stringify({ username, password }),
  })
  localStorage.setItem('knowly_token', data.token)
  localStorage.setItem('knowly_username', data.username)
  return data
}

export function logout(): void {
  localStorage.removeItem('knowly_token')
  localStorage.removeItem('knowly_username')
}

export function isLoggedIn(): boolean {
  return !!getToken()
}

// ── 清洗任务 ──

/** 创建清洗任务。支持多选：传数组时发 sourcePaths（Web 多选场景）；单个保持 sourcePath（兼容） */
export async function createJob(sourcePaths: string | string[], outputPath: string, sinks: string[]): Promise<{ jobId: string }> {
  const arr = Array.isArray(sourcePaths) ? sourcePaths : [sourcePaths]
  const body = arr.length === 1
    ? { sourcePath: arr[0], outputPath, sinks }
    : { sourcePaths: arr, outputPath, sinks }
  return request('/jobs', {
    method: 'POST',
    body: JSON.stringify(body),
  })
}

export async function getCurrentJob(): Promise<JobStatus | null> {
  return request('/jobs/current')
}

export async function getUnfinished(): Promise<boolean> {
  const data = await request<{ hasUnfinished: boolean }>('/jobs/unfinished')
  return data.hasUnfinished
}

export async function cancelJob(): Promise<void> {
  await request('/jobs/current/cancel', { method: 'POST' })
}

export async function getReport(outputPath: string): Promise<string | null> {
  return request(`/jobs/current/report?outputPath=${encodeURIComponent(outputPath)}`)
}

// ── SSE 进度 ──

export function subscribeProgress(onEvent: (event: string, data: string) => void): EventSource {
  const token = getToken()
  // EventSource 不支持自定义 header，通过 URL 传 token
  const es = new EventSource(`${API_BASE}/jobs/current/events?token=${token}`)
  es.onmessage = (e) => onEvent('message', e.data)
  es.addEventListener('PipelineStarted', (e) => onEvent('PipelineStarted', (e as MessageEvent).data))
  es.addEventListener('FileStarted', (e) => onEvent('FileStarted', (e as MessageEvent).data))
  es.addEventListener('FileCompleted', (e) => onEvent('FileCompleted', (e as MessageEvent).data))
  es.addEventListener('FileFailed', (e) => onEvent('FileFailed', (e as MessageEvent).data))
  es.addEventListener('PipelineFinished', (e) => onEvent('PipelineFinished', (e as MessageEvent).data))
  return es
}

// ── 分段预览 ──

export async function previewChunks(req: PreviewRequest): Promise<PreviewResponse> {
  return request('/preview/chunks', {
    method: 'POST',
    body: JSON.stringify(req),
  })
}

// ── 产出预览 ──

export async function getOutputMarkdown(outputPath: string): Promise<Array<{ name: string; preview: string }>> {
  return request(`/jobs/current/output/markdown?outputPath=${encodeURIComponent(outputPath)}`)
}

export async function getOutputChunks(outputPath: string, page = 1, size = 20): Promise<{ items: string[]; total: number }> {
  return request(`/jobs/current/output/chunks?outputPath=${encodeURIComponent(outputPath)}&page=${page}&size=${size}`)
}

// ── 文件浏览 ──

/** 获取用户主目录（跨平台的目录浏览起点） */
export async function getFileHome(): Promise<string> {
  const data = await request<{ path: string }>('/files/home')
  return data.path
}

export async function browseFiles(path: string): Promise<{ path: string; entries: FileEntry[] }> {
  return request(`/files/browse?path=${encodeURIComponent(path)}`)
}

// ── 配置模板 ──

export async function listTemplates(): Promise<Template[]> {
  return request('/templates')
}

export async function loadTemplate(name: string): Promise<{ name: string; content: string }> {
  return request(`/templates/${name}`)
}

export async function saveTemplate(name: string, content: string): Promise<void> {
  await request(`/templates/${name}`, {
    method: 'POST',
    body: JSON.stringify({ content }),
  })
}

export async function deleteTemplate(name: string): Promise<void> {
  await request(`/templates/${name}`, { method: 'DELETE' })
}

// ── 设置 ──

export async function getSettings(): Promise<AppSettings> {
  return request('/settings')
}

export async function updateSettings(settings: Partial<AppSettings>): Promise<void> {
  await request('/settings', {
    method: 'PUT',
    body: JSON.stringify(settings),
  })
}

export async function updatePassword(newPassword: string): Promise<void> {
  await request('/settings/password', {
    method: 'PUT',
    body: JSON.stringify({ newPassword }),
  })
}
