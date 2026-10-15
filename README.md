# scroll

Compose 的 Cupertino 手感滚动：**惯性 (fling)** + **越界回弹 (overscroll)**。挂到 `LazyColumn`、`Modifier.verticalScroll(...)` 这类带 `flingBehavior` / `overscrollEffect` 的容器上即可用（两个 `remember*` 都在 `scroll` 包里）。

## 引入

```kotlin
// settings.gradle.kts
maven { url = uri("https://jitpack.io") }

// 模块 build.gradle.kts，版本就是 Git Tag
implementation("com.github.mif9499:scroll:0.1.0")
```

需要 Compose（`animation` / `foundation` / `ui` / `runtime`，版本由使用方的 BOM 决定），`minSdk 24`。

## 用法

```kotlin
val listState = rememberLazyListState()

LazyColumn(
    state = listState,
    flingBehavior = rememberCupertinoFlingBehavior(),
    overscrollEffect = rememberCupertinoOverscrollEffect(),
) {
    // ...
}
```

## 参数

惯性用双指数衰减：`v(t) = v₀·(m·e^{-kt} - e^{-mkt})/(m-1)`，总位移 `v₀·(m+1)/(m·k)`。

- **`maxDeceleration`**（默认 `2.15f`）：式中的 `k`，即尾部的衰减率，决定惯性拖多长 —— 越大停得越快、同速度滚得越短。
- **`m`**（默认 `9f`）：快、慢两条指数的比例。它的作用是让 **a(0) = 0** —— 两个指数在 t=0 处精确相消，加速度从 0 起。`m` 越大减速峰值来得越早、越大，而尾部始终是纯 `e^{-kt}`，所以总位移随 `m` 变短。
- **`absVelocityThreshold`**（默认 `1f`）：停止阈值。速度降到它以下即停止。
