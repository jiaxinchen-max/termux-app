# Git HTTPS 大包推送说明

## 适用场景

普通 `git push` 出现类似错误：

```text
error: RPC failed; HTTP 400 curl 22 The requested URL returned error: 400
send-pack: unexpected disconnect while reading sideband packet
fatal: the remote end hung up unexpectedly
Everything up-to-date
```

如果 `Writing objects` 体积不大，且单个文件没有超过 GitHub 限制，这通常不是文件过大，而是 HTTPS 传输过程中 HTTP/2 或 chunked POST 被代理/网关断开。

## 先确认是否真的推送成功

失败日志里的 `Everything up-to-date` 可能误导，需要直接看远端 ref：

```bash
git rev-parse HEAD
git ls-remote origin <branch>
git status -sb
```

如果 `HEAD` 和 `ls-remote` 输出的 commit 不一致，本地仍显示 `[ahead N]`，说明没有推成功。

## 检查是否存在超大文件

```bash
git rev-list --objects origin/<branch>..HEAD \
  | git cat-file --batch-check='%(objecttype) %(objectname) %(objectsize) %(rest)' \
  | awk '$1=="blob" {printf "%.2f MiB\t%s\n", $3/1024/1024, $4}' \
  | sort -nr \
  | sed -n '1,30p'
```

如果存在接近或超过 `100 MiB` 的单文件，必须移除或改用 Git LFS；下面的 HTTP 参数不能绕过 GitHub 单文件限制。

## 推荐推送命令

```bash
git -c http.version=HTTP/1.1 \
  -c http.postBuffer=157286400 \
  push origin HEAD:<branch>
```

当前分支示例：

```bash
git -c http.version=HTTP/1.1 \
  -c http.postBuffer=157286400 \
  push origin HEAD:master-x11-submodule-tmp
```

## 和普通推送的区别

普通推送：

```bash
git push
```

使用 Git 当前默认 HTTP 行为，可能走 HTTP/2 或 chunked POST。大一些的 pack 在某些网络或网关下可能被中途断开。

专用推送：

```bash
git -c http.version=HTTP/1.1 -c http.postBuffer=157286400 push origin HEAD:<branch>
```

区别只在传输层：

- `http.version=HTTP/1.1`：避免 HTTP/2 sideband 断开问题。
- `http.postBuffer=157286400`：让 Git 使用足够大的请求缓冲，减少 chunked POST 触发网关错误的概率。
- `HEAD:<branch>`：明确把当前 commit 推到指定远端分支，避免当前 upstream 配置不清晰。

它不会改变 commit 内容，不会压缩图片，也不会绕过远端仓库限制。

## 推送后确认

```bash
git ls-remote origin <branch>
git status -sb
```

确认 `ls-remote` 的 commit 等于本地 `HEAD`，并且 `status` 不再显示 `[ahead N]`。

## 可选：写入本仓库配置

如果这个仓库经常遇到相同问题，可以写入本地配置：

```bash
git config http.version HTTP/1.1
git config http.postBuffer 157286400
```

这只影响当前仓库，不会修改远端，也不会影响 commit 内容。
