package com.lcs.tgcomic

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import coil.request.ImageRequest

/**
 * Coil 的 ImageRequest 必须「记住」再交给 AsyncImage。
 *
 * 直接在参数里写 `model = ImageRequest.Builder(ctx)...build()`，每次 recomposition 都会
 * 造一个新对象；Coil 把新对象当成新请求重新加载（有 crossfade 就再淡入一次），
 * 滚动时每一帧都可能重来一遍 —— 表现就是画面不停地闪。
 */
@Composable
fun rememberImage(
    vararg keys: Any?,
    crossfadeMs: Int = 0,
    builder: (Context) -> ImageRequest.Builder
): ImageRequest {
    val ctx = LocalContext.current
    return remember(ctx, crossfadeMs, *keys) {
        val b = builder(ctx)
        if (crossfadeMs > 0) b.crossfade(crossfadeMs) else b.crossfade(false)
        b.build()
    }
}
