# Yatori Android（维护版）

这是基于 [yuanycr/yatori-go-android](https://github.com/yuanycr/yatori-go-android) 继续维护的 Android 客户端。原项目由 [yatori-go-desktop](https://github.com/yuanycr/yatori-go-desktop) 提供 Go 移动核心；本仓库主要维护 Android 壳层、任务状态展示、日志可读性和 APK 构建。

## 本次维护

- 考试码轮询改为单飞请求，核心调用未返回时跳过下一轮，避免线程堆积。
- JS 到原生桥接使用有界线程池，过量请求返回明确的忙碌错误。
- Go 核心初始化增加进程内互斥，避免 Activity 和前台服务同时重复初始化。
- 前台服务在后台线程读取任务状态，每 5 秒更新通知；检测到进程重启时提示上次任务状态无法确认。
- 日志换行按词边界处理，避免窄屏下每个字符都被拆开。
- 使用独立 Android applicationId，允许与原版并行安装，数据目录相互独立。

## 构建

推送到 `main` 或手动运行 GitHub Actions 的 **Android APK** 工作流，会：

1. 检出固定版本的桌面核心并运行 mobilecore 测试。
2. 为 arm64 构建 Go AAR，并生成版本与 SHA-256 元数据。
3. 使用 JDK 21 和 Gradle 8.7 构建调试签名 APK。
4. 上传 APK 为 Actions 构建产物。

APK 可在仓库的 **Actions → Android APK → Artifacts** 下载。首次构建完成后，确认日志和任务状态表现，再决定是否创建 Release。

> APK 使用调试签名；改包名后可与原版并行安装，但不会继承原版应用的数据。

## 来源与许可

原始 Android 工程及其界面资源来自 [yuanycr/yatori-go-android](https://github.com/yuanycr/yatori-go-android)，项目 README 声明 MIT License。核心 AAR 来自 [yuanycr/yatori-go-desktop](https://github.com/yuanycr/yatori-go-desktop)。保留原作者和上游项目署名；请同时遵守上游项目适用的许可证、平台规则及账号授权范围。

本维护仓库不复制或修改桌面核心中的平台业务逻辑。
