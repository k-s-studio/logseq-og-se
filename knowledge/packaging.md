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

版本號來自 `src/main/frontend/version.cljs`。原版 `gulp electronMaker` 會把它寫進 `static/package.json`；手動流程若改了版本號，要自己同步。

## 不要碰的指令（其他平台）
`release-app`、`run-android-release`、`run-ios-release`、`ios:dev`、`android:dev`、`npx cap ...`

## 已知陷阱
- **`gulp build` 會清空 `static/`**：只保留 `static/node_modules` 和 `static/yarn.lock`，**連前一次的打包產出 `static/out/` 也會刪掉**，要保留的安裝檔請先移到別處。
- **Node 24 下 forge make 會無聲結束**：解壓 Electron zip 時，extract-zip 2.0.1 的 Promise 永遠不會 resolve，程序 exit 0、沒有產出。看到「Finalizing package」後就結束、而且沒有 `static/out`，就是這個問題 → 改用 Node 22。排查用 `$env:DEBUG="electron-packager,electron-forge:*"`
- `static/` 執行 `yarn install` 時 `@bitdisaster/exe-icon-extractor` 編譯失敗（C2664）：它是 `electron-wix-msi` 的選用依賴，只影響 msi，可以忽略。
- 打包後若 App 內 git 無法運作，先確認 `static/out/Logseq-OG-SE-win32-x64/resources/app/node_modules/dugite/git/cmd/git.exe` 存在。
