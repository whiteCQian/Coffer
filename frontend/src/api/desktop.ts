import type { WorkCopyView } from './workCopies'
export interface NativeSelection { id: string; name: string; size: number; targetPath: string; sha256: string }
export interface NativeReceipt { name: string; targetPath: string; ok: boolean; error?: string }
export interface DesktopBackupResult { status: string; packagePath: string; packageSha256: string; independent: boolean; users: number; files: number; ledgerRows: number; backupId: string }
export interface DesktopStatus { phase: string; error: string | null; dataDirectory: string; initialized: boolean; nonempty: boolean; version: string }
declare global {
  interface Window {
    cofferDesktop?: {
      status(): Promise<DesktopStatus>
      setupToken(): Promise<string>
      selectFiles(): Promise<NativeSelection[]>
      previewDroppedFiles(files: File[]): Promise<NativeSelection[]>
      commitInbox(items: { id: string; targetPath: string }[]): Promise<NativeReceipt[]>
      openWorkCopy(id: string): Promise<WorkCopyView>
      chooseBackupDestination(): Promise<{ directory: string } | null>
      createBackup(options: { password: string; localSnapshot: boolean }): Promise<DesktopBackupResult | null>
    }
  }
}
export const desktopBridge = window.cofferDesktop
