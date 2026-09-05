import axios, { type AxiosRequestConfig, type AxiosResponse } from 'axios'
import { ElMessage } from 'element-plus'

/**
 * 统一请求封装
 *
 * 前端开发规范：只用 src/utils/request.ts，不新建 axios 实例。
 */
const service = axios.create({
  baseURL: '/api',
  timeout: 15000,
  headers: {
    'Content-Type': 'application/json'
  }
})

// 请求拦截器
service.interceptors.request.use(
  (config) => {
    // FormData 时清除默认 Content-Type，让浏览器自动设置 multipart/form-data + boundary
    if (config.data instanceof FormData) {
      config.headers.delete('Content-Type')
    }
    return config
  },
  (error) => {
    return Promise.reject(error)
  }
)

// 响应拦截器
service.interceptors.response.use(
  (response: AxiosResponse) => {
    const res = response.data
    // 管理面统一返回 R<T> 格式
    if (res && typeof res === 'object' && 'code' in res) {
      if (res.code === 200) {
        return res.data
      } else {
        ElMessage.error(res.msg || '请求失败')
        return Promise.reject(new Error(res.msg || '请求失败'))
      }
    }
    // 鉴权面直接返回数据（200 + clientMode 模式）
    return res
  },
  (error) => {
    const status = error.response?.status
    const data = error.response?.data

    if (status === 401) {
      ElMessage.error(data?.message || '签名验证失败')
    } else if (status === 400) {
      ElMessage.error(data?.message || '请求参数错误')
    } else if (status === 409) {
      ElMessage.warning(data?.message || '并发冲突，请稍后重试')
    } else if (status === 500) {
      ElMessage.error('服务内部错误，请稍后重试')
    } else {
      ElMessage.error(error.message || '网络错误')
    }

    return Promise.reject(error)
  }
)

/** GET 请求 */
export function get<T = any>(url: string, params?: any, config?: AxiosRequestConfig): Promise<T> {
  // 拦截器（line 30-42）已经把 response.data 解包为 T，这里加 as 断言告诉 TS 即可
  return service.get(url, { params, ...config }) as unknown as Promise<T>
}

/** POST 请求 */
export function post<T = any>(url: string, data?: any, config?: AxiosRequestConfig): Promise<T> {
  return service.post(url, data, config) as unknown as Promise<T>
}

/** PUT 请求 */
export function put<T = any>(url: string, data?: any, config?: AxiosRequestConfig): Promise<T> {
  return service.put(url, data, config) as unknown as Promise<T>
}

/** DELETE 请求 */
export function del<T = any>(url: string, config?: AxiosRequestConfig): Promise<T> {
  return service.delete(url, config) as unknown as Promise<T>
}

export default service
