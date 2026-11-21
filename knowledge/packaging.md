# 打包（Windows x64 桌面端）

**不要直接跑 `yarn release-electron`**：它最後的 `electron-forge make` 會跑 WiX maker（本機沒裝），而且在 Node 24 下會失敗。改用下方的步驟。

## 切換到 Node 22（只限當前 shell）
`electron-forge make` 必須用 Node 22，其他步驟用全域的 24 即可。禁止 `nvm use`（nvm-windows 會改全域 symlink，影響其他專案）。

```powershell
$env:Path = "$env:NVM_HOME\v22.23.5;" + $env:Path; node -v
```

## 步驟（根目錄起算）
```powershell
yarn install                       # 首次或依賴變更時；約 12 分鐘
yarn gulp:build
yarn cljs:release-electron         # 約 7 分鐘
cd static
yarn install                       # 首次或 resources/package.json 變更時
cd node_modules/dugite; yarn run postinstall; cd ../..   # 下載 dugite 自帶的 git.exe
# 以下要先切 Node 22（見上方）
npx electron-forge make --targets "@electron-forge/maker-squirrel,@electron-forge/maker-zip"
```

## 產出
產出在 `static/out/make/`：
- `squirrel.windows/x64/Logseq-OG-SE-<ver> Setup.exe`（安裝檔）
- `zip/win32/x64/Logseq-OG-SE-win32-x64-<ver>.zip`（免安裝版，已驗證可執行）
- 解壓前的 App：`static/out/Logseq-OG-SE-win32-x64/`

## 版本號（手動維護）
沒有任何機制會自動遞增版本號。基準值是 `src/main/frontend/version.cljs` 的 `(defonce version "x.y.z")`，**升版時手動改兩處，兩處必須一致**，然後重編 cljs（版本號是編進程式碼的）：
- `src/main/frontend/version.cljs`：App 內顯示的版本，也是更新檢查拿去比較的本機版本
- `resources/package.json` 的 `version`：決定安裝檔、nupkg、exe 的版本。`gulp build` 會把它複製成 `static/package.json`，直接改 `static/` 的那份會在下次 `gulp build` 被清掉

只改一處的後果：只改 `package.json` → 安裝檔是新版號、App 內顯示舊版；只改 `version.cljs` → 相反。

不用改：根目錄 `package.json`（打包不讀它）、Android／iOS 的版本欄位（只有行動端用）。

原版 `gulp electronMaker` 會自動把 `version.cljs` 寫進 `static/package.json`，但本流程拆開跑 `gulp:build` + `cljs:release-electron` + `forge make`，沒有經過它，所以要自己同步。

## 更新來源
更新檢查指向本 repo（`k-s-studio/logseq-og-se`）的 GitHub Releases，**共四處須一致**：`src/electron/electron/core.cljs` 的 `:repo`（手動檢查與啟動時預檢）、`resources/package.json` 的 `repository`（`update-electron-app` 讀它）、`resources/forge.config.js` 的 `publisher-github`、`settings.cljs` 裡的 Releases 連結。

`update.electronjs.org` 官方文件載明：repo 必須公開；Windows 資產須為 `.zip` 或 `.exe`，檔名含 `-win32` 與架構（如 `-x64`）。**未載明、尚待實測**：是否忽略 pre-release（forge 的 `publisher-github` 設了 `prerelease: true`）、是否需要 `RELEASES`／`nupkg`、沒有 Release 時回傳什麼狀態碼。

## 不要碰的指令（其他平台）
`release-app`、`run-android-release`、`run-ios-release`、`ios:dev`、`android:dev`、`npx cap ...`

## 已知陷阱
- **`gulp build` 會清空 `static/`**：只保留 `static/node_modules` 和 `static/yarn.lock`，**連前一次的打包產出 `static/out/` 也會刪掉**，要保留的安裝檔請先移到別處。
- **Node 24 下 forge make 會無聲結束**：解壓 Electron zip 時，extract-zip 2.0.1 的 Promise 永遠不會 resolve，程序 exit 0、沒有產出。看到「Finalizing package」後就結束、而且沒有 `static/out`，就是這個問題 → 改用 Node 22。排查用 `$env:DEBUG="electron-packager,electron-forge:*"`
- `static/` 執行 `yarn install` 時 `@bitdisaster/exe-icon-extractor` 編譯失敗（C2664）：它是 `electron-wix-msi` 的選用依賴，只影響 msi，可以忽略。
- 打包後若 App 內 git 無法運作，先確認 `static/out/Logseq-OG-SE-win32-x64/resources/app/node_modules/dugite/git/cmd/git.exe` 存在。
