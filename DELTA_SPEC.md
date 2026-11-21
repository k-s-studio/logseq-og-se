# DELTA_SPEC：相對上游 Logseq OG 的客製化規格

登記本分支與上游 [logseq/og](https://github.com/logseq/og) 的差異。新增客製化時在此加一項：摘要少於 100 字，不列出改動的檔案路徑。

## 名稱
- **產品名稱**：`Logseq-OG` 改為 `Logseq-OG-SE`，涵蓋安裝檔、zip、exe 檔名與 Windows 應用程式顯示名稱；打包設定內各處名稱須同步。
- **顯示名稱**：執行期名稱與「關於」標題改為 `Logseq OG SE`；AppData 使用者資料夾隨之更名，舊設定不會自動遷移。

## 識別碼（凡字面含 `logseq-og` 者一律改為 `logseq-og-se`）
- **協定 scheme**：深層連結改為 `logseq-og-se://`，含行動端；主程序、前端、打包設定三處必須一致。
- **設定資料夾**：`~/.logseq-og` 改為 `~/.logseq-og-se`，舊資料夾不會自動遷移，需要請自行複製。
- **套件與應用 id**：套件名稱、macOS bundle id、Windows AppUserModelId 改為含 `logseq-og-se` 的值。
- **登入回呼網址**：官方登入（Cognito）的 redirect_uri 改用新 scheme；伺服器端是否登記未知，登入可能失敗。
- **CI 發佈檔名**：release 檔名前綴 `Logseq-OG-` 改為 `Logseq-OG-SE-`，artifact 名改為 `logseq-og-se-*-builds`。

## CI（GitHub Actions）
- **桌面版發佈**：只能手動觸發，只建置 Windows x64（Squirrel 安裝檔與 zip），不產 msi、不簽章；已移除 macOS、Linux、Android 的打包與 nightly。
- **已移除**：Docker 映像、Cloudflare Pages 部署、Sentry sourcemap 上傳，以及 issue／PR 的自動標籤、範本、過期清理與贊助連結設定。
- **Docker 文件**：說明文件保留（README 有連結），內容描述上游映像，不適用本分支。
- **沿用上游**：測試、lint、E2E 與各子套件的 CI 維持不變。
- **行動端流程**：Android、iOS 腳本（含簽章步驟）原封保留，改以 GitHub 網頁停用而非改檔；停用狀態不在版控內，要用時再啟用。

## 刻意維持上游原值
- 行動端 `com.logseq.og` 系列 id（牽涉簽署）、WiX `upgradeCode`、上游 repo 路徑 `logseq/og`（自動更新仍指向上游）。
