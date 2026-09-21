/**
 * The one place a request is made and a failure is turned into an error a view
 * can show.
 */

export class ApiError extends Error {
  readonly status: number | null
  readonly detail: string | null

  constructor(message: string, status: number | null = null, detail: string | null = null) {
    super(message)
    this.name = 'ApiError'
    this.status = status
    this.detail = detail
  }
}

/** What a reader is told when the failure is not an HTTP status at all. */
export function describeError(error: unknown): string {
  if (error instanceof ApiError) {
    return error.detail ? `${error.message}: ${error.detail}` : error.message
  }
  if (error instanceof Error) {
    return error.message
  }
  return 'unexpected error'
}

/**
 * Reads the `detail` of an RFC 9457 problem detail, which is how this API
 * reports every failure, and falls back to the status text when the body is not
 * the document the contract promises.
 */
async function problemDetail(response: Response): Promise<string | null> {
  try {
    const body: unknown = await response.json()
    if (body && typeof body === 'object' && 'detail' in body) {
      const detail = (body as { detail: unknown }).detail
      return typeof detail === 'string' ? detail : null
    }
  } catch {
    // A body that is not JSON is not a reason to lose the status of the response.
  }
  return null
}

/**
 * @param url endpoint to read
 * @param signal cancels the request when the caller goes away
 * @returns the parsed JSON body
 * @throws ApiError when the response is not a success
 */
export async function getJson<T>(url: string, signal?: AbortSignal): Promise<T> {
  let response: Response
  try {
    response = await fetch(url, { headers: { Accept: 'application/json' }, signal })
  } catch (transportFailure) {
    throw new ApiError(`cannot reach the API at ${url}`, null, describeError(transportFailure))
  }
  if (!response.ok) {
    const status = `${response.status} ${response.statusText}`.trim()
    throw new ApiError(`the API answered ${status}`, response.status, await problemDetail(response))
  }
  return (await response.json()) as T
}
