!macro customWelcomePage
  !insertmacro MUI_PAGE_WELCOME
!macroend

!macro customUnWelcomePage
  !define MUI_WELCOMEPAGE_TITLE "卸载 Coffer"
  !define MUI_WELCOMEPAGE_TEXT "卸载只移除程序文件。账号、数据库、文件库和密钥会保留在用户数据目录，重新安装可继续使用。请勿直接删除唯一密钥或备份。"
  !insertmacro MUI_UNPAGE_WELCOME
!macroend

!macro customInstall
  DetailPrint "Coffer 数据独立于安装目录，安装和升级不清除用户数据。"
!macroend

!macro customHeader
  !ifndef BUILD_UNINSTALLER
    Function CofferCheckInstallDirectory
      Push $R0
      Push $R1
      StrCpy $R0 $INSTDIR
      coffer_check_parent:
        ${If} ${FileExists} "$R0\.coffer-desktop.json"
        ${OrIf} ${FileExists} "$R0\.coffer-library.json"
        ${OrIf} ${FileExists} "$R0\.coffer-encryption-key"
          MessageBox MB_ICONSTOP|MB_OK "安装目录不能位于业务数据库或文件库中。请选择独立的程序目录，数据已保留。" /SD IDOK
          SetErrorLevel 2
          Quit
        ${EndIf}
        ${GetParent} "$R0" $R1
        ${If} $R1 == ""
        ${OrIf} $R1 == $R0
          Goto coffer_parent_done
        ${EndIf}
        StrCpy $R0 $R1
        Goto coffer_check_parent
      coffer_parent_done:
      ${IfNot} ${FileExists} "$INSTDIR\Coffer.exe"
        FindFirst $R0 $R1 "$INSTDIR\*"
        coffer_check_empty:
          ${If} $R1 == ""
            Goto coffer_empty_done
          ${EndIf}
          ${If} $R1 != "."
          ${AndIf} $R1 != ".."
            FindClose $R0
            MessageBox MB_ICONSTOP|MB_OK "安装仅允许空目录或已有 Coffer 程序目录，不能覆盖其他文件。请选择独立空目录。" /SD IDOK
            SetErrorLevel 2
            Quit
          ${EndIf}
          FindNext $R0 $R1
          Goto coffer_check_empty
        coffer_empty_done:
        FindClose $R0
      ${EndIf}
      Pop $R1
      Pop $R0
    FunctionEnd
    Function CofferGuardPage
      Call CofferCheckInstallDirectory
      Abort
    FunctionEnd
  !endif
!macroend

!macro customInit
  Call CofferCheckInstallDirectory
!macroend

!macro customPageAfterChangeDir
  Page custom CofferGuardPage
!macroend

!macro customUnInit
  ${If} ${FileExists} "$INSTDIR\.coffer-desktop.json"
  ${OrIf} ${FileExists} "$INSTDIR\.coffer-library.json"
  ${OrIf} ${FileExists} "$INSTDIR\data\.coffer-desktop.json"
  ${OrIf} ${FileExists} "$INSTDIR\library\.coffer-library.json"
    MessageBox MB_ICONSTOP|MB_OK "程序目录中发现业务数据，卸载已停止。请先迁移并核对完整数据与密钥，避免误删。" /SD IDOK
    SetErrorLevel 2
    Quit
  ${EndIf}
!macroend
