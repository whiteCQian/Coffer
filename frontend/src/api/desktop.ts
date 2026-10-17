import type { WorkCopyView } from './workCopies'
export interface NativeSelection { id: string; name: string; size: number; targetPath: string; sha256: string }
export interface NativeReceipt { name: string; targetPath: string; ok: boolean; fileId?: number; error?: string }
export interface DesktopBackupResult { status: string; packagePath: string; packageSha256: string; independent: boolean; users: number; files: number; ledgerRows: number; backupId: string }
export interface DesktopStatus { phase: string; error: string | null; dataDirectory: string; initialized: boolean; nonempty: boolean; version: string }
declare global {
  interface Window {
    cofferDesktop?: {
      status(): Promise<DesktopStatus>
      getTheme?(): Promise<'light' | 'dark' | null>
      saveTheme?(theme: 'light' | 'dark'): Promise<void>
      getRecentTags?(): Promise<string[]>
      saveRecentTags?(names: string[]): Promise<void>
      getPaneWidths?(): Promise<Record<string, number>>
      savePaneWidth?(key: string, value: number): Promise<void>
      manageLibrary?(): Promise<DesktopStatus>
      selectFiles(): Promise<NativeSelection[]>
      previewDroppedFiles(files: File[]): Promise<NativeSelection[]>
      commitInbox(items: { id: string; targetPath: string; moveSource?: boolean }[]): Promise<NativeReceipt[]>
      openWorkCopy(id: string): Promise<WorkCopyView>
      openFile?(id: number, revision?: number): Promise<{ fileId: number; revision: number; status: string; errorCode?: string | null; message: string }>
      chooseBackupDestination(): Promise<{ directory: string } | null>
      createBackup(options: { password: string; localSnapshot: boolean }): Promise<DesktopBackupResult | null>
    }
  }
}
export const desktopBridge = window.cofferDesktop
