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

## 更新
- **更新來源**：手動檢查、啟動時自動更新、發佈設定與錯誤提示連結，全改指向本 repo 的 GitHub Releases，不再查詢上游。
- **版本號**：由本分支自行維護、獨立於上游；更新檢查拿它與本 repo 最新 Release 比較。

## CI（GitHub Actions）
- **桌面版發佈**：只能手動觸發，只建置 Windows x64（Squirrel 安裝檔與 zip），不產 msi、不簽章；已移除 macOS、Linux、Android 的打包與 nightly。
- **已移除**：Docker 映像、Cloudflare Pages 部署、Sentry sourcemap 上傳，以及 issue／PR 的自動標籤、範本、過期清理與贊助連結設定。
- **Docker 文件**：說明文件保留（README 有連結），內容描述上游映像，不適用本分支。
- **沿用上游**：測試、lint、E2E 與各子套件的 CI 維持不變。
- **行動端流程**：Android、iOS 腳本（含簽章步驟）原封保留，改以 GitHub 網頁停用而非改檔；停用狀態不在版控內，要用時再啟用。

## Git 整合（桌面端）
- **不自帶 git**：移除 dugite，改用 PATH 上的系統 git（需 2.31 以上），找不到時以錯誤通知提示。
- **綁定取代 init**：不再自動 `git init` 或搬移 `.git`。圖表須在「設定 > 版本控制」綁定既有的 git 儲藏庫，未綁定時所有 git 功能不動作。
- **綁定檔**：存在圖表內的 `logseq/.git-binding.edn`，只在本機有效，不會進入 ref。圖表在儲藏庫內時記相對路徑，否則記絕對路徑。
- **獨立版本樹**：提交只寫入該儲藏庫的 `refs/logseq-og-se/<名稱>`，不動到分支、HEAD、索引與工作目錄；ref 樹根即圖表根目錄。
- **ref 名稱**：預填清理過的資料夾名稱，只允許單層；不合法或已存在（不分大小寫）時表單擋下，須換名。
- **exclude**：在儲藏庫的 `info/exclude` 加入綁定檔規則；圖表位於儲藏庫內時也排除圖表目錄。圖表若已被分支追蹤只提出警告，不代為取消追蹤。圖表不能是儲藏庫根目錄。
- **指令範圍**：外掛 git API、shell 的 git 指令、版本歷史與提交視窗都作用在圖表的 ref；每次執行前把 HEAD 重設回該 ref，但不限制指令內容。
- **作者身分**：缺少身分時的設定視窗改寫入綁定儲藏庫的 `--local` 設定，不再寫入全域設定。
- **工具列選單**：桌面端頂部有 Git 圖示。未綁定時引導至設定綁定；已綁定時可用預設訊息立即提交，並選擇遠端 push 或 pull 圖表的 ref。
- **push**：只推送圖表的 ref，遠端較新時拒絕並提示先 pull，不提供 force push。
- **pull**：一律 rebase，拉取前先自動提交；文字衝突以 union 合併保留兩邊內容。無法合併的檔案（如二進位、刪改衝突）會中止 rebase 並列出檔案，圖表維持原狀。
- **遠端記憶**：上次使用的遠端記在綁定檔，預設 `origin`。

## Logseq Sync（停用）
- **同步與登入**：官方 Logseq Sync 一律停用（開發版亦同），設定中的登入／登出與 Beta 同步開關隱藏；程式碼保留，未刪除。
- **頁面歷史**：只從綁定儲藏庫的圖表 ref 讀取；版本控制頁的提示改為說明此來源，其他語言改用英文提示。

## 刻意維持上游原值
- 行動端 `com.logseq.og` 系列 id（牽涉簽署）、WiX `upgradeCode`。
