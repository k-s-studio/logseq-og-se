# 開發

修改程式碼時使用熱重載的開發模式，不需要打包。

## 啟動
兩個終端機：
1. `yarn electron-watch`：等 `:app` 與 `:electron` 都出現 `Build completed`
2. `yarn dev-electron-app`：開啟開發用 Electron 視窗

## 更新方式
- renderer（`src/main/frontend/**`）存檔即熱重載。
- 主程序（`src/electron/**`）改動後要關掉視窗重跑步驟 2，否則 `defonce` 的計時器／IPC 可能還是舊的。

## 檢查與測試
- `yarn cljs:lint`（clj-kondo）
- `yarn test`

## 已知陷阱
- **一定用 `electron-watch`，不要用 `watch`**：`watch` 少了 `:asset-path "./js"`，Electron 視窗會白屏。
