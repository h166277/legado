package io.legado.app.ui.book.character.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.data.entities.BookCharacter
import io.legado.app.help.character.BookCharacterProfileMeta
import io.legado.app.help.readaloud.BookDubbingChapterHelper
import io.legado.app.help.readaloud.speech.SpeechRoute

@Immutable
data class DubbingRoleUi(
    val character: BookCharacter,
    val color: Color,
    val voiceSummary: String,
    val routeSummary: String
)

@Immutable
data class DubbingChapterUi(
    val status: BookDubbingChapterHelper.ChapterDubStatus
)

private val ROLE_PALETTE = listOf(
    Color(0xFFE85D5D),
    Color(0xFF4C8DFF),
    Color(0xFF3CBF6E),
    Color(0xFFF0B429),
    Color(0xFF9B6BFF),
    Color(0xFFFF6B9D),
    Color(0xFF2EC4B6),
    Color(0xFFFF8C42)
)

fun roleColorFor(character: BookCharacter, index: Int): Color {
    if (character.name == "旁白") return ROLE_PALETTE[0]
    val key = character.id.takeIf { it > 0 } ?: character.name.hashCode().toLong()
    val idx = Math.floorMod((key + index).toInt(), ROLE_PALETTE.size)
    return ROLE_PALETTE[idx]
}

fun voiceSummaryOf(character: BookCharacter): String {
    val personality = character.personality.trim()
    if (personality.isNotBlank()) return personality
    val age = BookCharacterProfileMeta.ageOf(character)
        .trim()
        .let { raw ->
            when {
                raw.isBlank() -> ""
                raw.startsWith("约") || raw.contains("岁") || raw.length <= 3 -> raw
                else -> "约$raw"
            }
        }
    val gender = character.genderLabel().takeIf { it != "未知" }.orEmpty()
    return listOf(gender, age)
        .filter { it.isNotBlank() }
        .joinToString("，")
        .ifBlank { "未分析音色" }
}

@Composable
fun BookDubbingScreen(
    bookName: String,
    author: String,
    roles: List<DubbingRoleUi>,
    chapters: List<DubbingChapterUi>,
    busyText: String,
    chapterConcurrency: Int,
    segmentConcurrency: Int,
    onBack: () -> Unit,
    onOpenSettings: () -> Unit,
    onAnalyzeAll: () -> Unit,
    onDubAll: () -> Unit,
    onOneClickAnalyzeRoles: () -> Unit,
    onRefresh: () -> Unit,
    onSearchChange: (String) -> Unit,
    searchQuery: String,
    onMergeRoles: () -> Unit,
    onDeleteRole: (BookCharacter) -> Unit,
    onAnalyzeRole: (BookCharacter) -> Unit,
    onPreviewRole: (BookCharacter) -> Unit,
    onReassignRole: (BookCharacter) -> Unit,
    onOpenRole: (BookCharacter) -> Unit,
    onAnalyzeChapter: (Int) -> Unit,
    onOpenChapter: (Int) -> Unit,
    onChapterConcurrencyChange: (Int) -> Unit,
    onSegmentConcurrencyChange: (Int) -> Unit
) {
    val style = rememberCharacterStyle()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(style.colors.page)
            .navigationBarsPadding()
    ) {
        DubbingTopBar(
            title = bookName.ifBlank { "配音" },
            subtitle = if (author.isBlank()) "作者未知" else "作者：$author",
            onBack = onBack,
            onOpenSettings = onOpenSettings
        )
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ActionChip(
                        text = "全部 AI 分析",
                        filled = true,
                        modifier = Modifier.weight(1f),
                        onClick = onAnalyzeAll
                    )
                    ActionChip(
                        text = "全部配音",
                        filled = false,
                        modifier = Modifier.weight(1f),
                        onClick = onDubAll
                    )
                }
            }
            item {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("AI分析章节并发", color = style.colors.subText, fontSize = 12.sp)
                    ConcurrencyPicker(chapterConcurrency, onChapterConcurrencyChange)
                    Text("配音段并发", color = style.colors.subText, fontSize = 12.sp)
                    ConcurrencyPicker(segmentConcurrency, onSegmentConcurrencyChange)
                }
                Text(
                    "默认 1 = 不并发",
                    color = style.colors.subText,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
            if (busyText.isNotBlank()) {
                item {
                    Surface(
                        color = style.colors.cardAlt,
                        shape = RoundedCornerShape(style.smallRadius)
                    ) {
                        Text(
                            busyText,
                            color = style.colors.accent,
                            fontSize = 13.sp,
                            modifier = Modifier.padding(12.dp)
                        )
                    }
                }
            }
            item {
                Surface(
                    color = style.colors.card,
                    shape = RoundedCornerShape(style.radius),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier = Modifier.padding(14.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "角色配音设置",
                                color = style.colors.text,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 16.sp
                            )
                            Text(
                                "${roles.size} 个角色",
                                color = style.colors.subText,
                                fontSize = 13.sp
                            )
                        }
                        Spacer(Modifier.height(10.dp))
                        SearchField(
                            value = searchQuery,
                            onValueChange = onSearchChange,
                            placeholder = "搜索角色…"
                        )
                        Spacer(Modifier.height(10.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceEvenly
                        ) {
                            TextButton(onClick = onMergeRoles) {
                                Text("合并角色", color = style.colors.accent, fontSize = 13.sp)
                            }
                            TextButton(onClick = onOneClickAnalyzeRoles) {
                                Text("一键分析", color = style.colors.accent, fontSize = 13.sp)
                            }
                            TextButton(onClick = onRefresh) {
                                Text("刷新", color = style.colors.accent, fontSize = 13.sp)
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                        if (roles.isEmpty()) {
                            Text(
                                "还没有角色。先对章节做 AI 分析，或手动添加角色卡。",
                                color = style.colors.subText,
                                fontSize = 13.sp,
                                modifier = Modifier.padding(vertical = 12.dp)
                            )
                        } else {
                            roles.forEach { role ->
                                RoleCard(
                                    role = role,
                                    onDelete = { onDeleteRole(role.character) },
                                    onAnalyze = { onAnalyzeRole(role.character) },
                                    onPreview = { onPreviewRole(role.character) },
                                    onReassign = { onReassignRole(role.character) },
                                    onOpen = { onOpenRole(role.character) }
                                )
                                Spacer(Modifier.height(10.dp))
                            }
                        }
                    }
                }
            }
            item {
                Text(
                    "章节分析 / 配音状态",
                    color = style.colors.text,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 15.sp
                )
            }
            items(chapters, key = { it.status.chapterIndex }) { item ->
                ChapterRow(
                    item = item,
                    onAnalyze = { onAnalyzeChapter(item.status.chapterIndex) },
                    onOpen = { onOpenChapter(item.status.chapterIndex) }
                )
            }
        }
    }
}

@Composable
private fun DubbingTopBar(
    title: String,
    subtitle: String,
    onBack: () -> Unit,
    onOpenSettings: () -> Unit
) {
    val style = rememberCharacterStyle()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        TextButton(onClick = onBack) {
            Text("←", color = style.colors.text, fontSize = 20.sp)
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                title,
                color = style.colors.text,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                subtitle,
                color = style.colors.subText,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        TextButton(onClick = onOpenSettings) {
            Text("设置", color = style.colors.accent)
        }
    }
}

@Composable
private fun ActionChip(
    text: String,
    filled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val style = rememberCharacterStyle()
    if (filled) {
        Button(
            onClick = onClick,
            modifier = modifier.height(44.dp),
            shape = RoundedCornerShape(style.smallRadius),
            colors = ButtonDefaults.buttonColors(containerColor = style.colors.accent)
        ) {
            Text(text, color = Color.White, fontSize = 14.sp)
        }
    } else {
        OutlinedButton(
            onClick = onClick,
            modifier = modifier.height(44.dp),
            shape = RoundedCornerShape(style.smallRadius)
        ) {
            Text(text, color = style.colors.text, fontSize = 14.sp)
        }
    }
}

@Composable
private fun ConcurrencyPicker(value: Int, onChange: (Int) -> Unit) {
    val style = rememberCharacterStyle()
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(style.colors.card)
            .border(1.dp, style.colors.stroke, RoundedCornerShape(10.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            "−",
            color = style.colors.accent,
            modifier = Modifier
                .clickable { onChange((value - 1).coerceAtLeast(1)) }
                .padding(4.dp)
        )
        Text(
            "$value",
            color = style.colors.text,
            modifier = Modifier.padding(horizontal = 8.dp)
        )
        Text(
            "+",
            color = style.colors.accent,
            modifier = Modifier
                .clickable { onChange((value + 1).coerceAtMost(8)) }
                .padding(4.dp)
        )
    }
}

@Composable
private fun SearchField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String
) {
    val style = rememberCharacterStyle()
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(style.colors.page)
            .border(1.dp, style.colors.stroke, RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        if (value.isEmpty()) {
            Text(placeholder, color = style.colors.subText, fontSize = 14.sp)
        }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = TextStyle(color = style.colors.text, fontSize = 14.sp),
            cursorBrush = SolidColor(style.colors.accent),
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun RoleCard(
    role: DubbingRoleUi,
    onDelete: () -> Unit,
    onAnalyze: () -> Unit,
    onPreview: () -> Unit,
    onReassign: () -> Unit,
    onOpen: () -> Unit
) {
    val style = rememberCharacterStyle()
    val character = role.character
    Surface(
        color = style.colors.page,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, style.colors.stroke, RoundedCornerShape(14.dp))
            .clickable(onClick = onOpen)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                ROLE_PALETTE.forEach { c ->
                    Box(
                        modifier = Modifier
                            .size(12.dp)
                            .clip(CircleShape)
                            .background(c)
                            .then(
                                if (c == role.color) {
                                    Modifier.border(1.5.dp, style.colors.text, CircleShape)
                                } else Modifier
                            )
                    )
                }
                Spacer(Modifier.weight(1f))
                Text(
                    "删除",
                    color = style.colors.danger,
                    fontSize = 12.sp,
                    modifier = Modifier.clickable(onClick = onDelete)
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(role.color)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    character.displayName(),
                    color = style.colors.text,
                    fontWeight = FontWeight.Medium,
                    fontSize = 15.sp,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                MiniButton("重配音", onReassign)
            }
            Spacer(Modifier.height(8.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    color = style.colors.cardAlt,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        role.voiceSummary,
                        color = style.colors.subText,
                        fontSize = 12.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)
                    )
                }
                MiniButton("AI分析", onAnalyze)
                MiniButton("试听", onPreview)
            }
            if (role.routeSummary.isNotBlank()) {
                Text(
                    role.routeSummary,
                    color = style.colors.subText,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 6.dp)
                )
            }
        }
    }
}

@Composable
private fun MiniButton(text: String, onClick: () -> Unit) {
    val style = rememberCharacterStyle()
    Surface(
        color = style.colors.card,
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .border(1.dp, style.colors.stroke, RoundedCornerShape(10.dp))
    ) {
        Text(
            text,
            color = style.colors.accent,
            fontSize = 12.sp,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)
        )
    }
}

@Composable
private fun ChapterRow(
    item: DubbingChapterUi,
    onAnalyze: () -> Unit,
    onOpen: () -> Unit
) {
    val style = rememberCharacterStyle()
    val st = item.status
    Surface(
        color = style.colors.card,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    st.title.ifBlank { "第 ${st.chapterIndex + 1} 章" },
                    color = style.colors.text,
                    fontSize = 14.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    StatusDot(
                        label = "分析",
                        ok = st.analyzed,
                        extra = if (st.segmentCount > 0) "${st.segmentCount} 段" else ""
                    )
                    StatusDot(
                        label = "配音",
                        ok = st.analyzed && st.segmentCount > 0,
                        extra = st.status.takeIf { it.isNotBlank() && !st.analyzed }.orEmpty()
                    )
                }
            }
            TextButton(onClick = onAnalyze) {
                Text(if (st.analyzed) "重分析" else "分析", color = style.colors.accent)
            }
        }
    }
}

@Composable
private fun StatusDot(label: String, ok: Boolean, extra: String) {
    val style = rememberCharacterStyle()
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(if (ok) Color(0xFF3CBF6E) else style.colors.subText.copy(alpha = 0.45f))
        )
        Spacer(Modifier.width(4.dp))
        Text(
            buildString {
                append(label)
                if (extra.isNotBlank()) append(" · ").append(extra)
            },
            color = style.colors.subText,
            fontSize = 12.sp
        )
    }
}

fun buildRoleUiList(characters: List<BookCharacter>): List<DubbingRoleUi> {
    return characters.mapIndexed { index, character ->
        val route = SpeechRoute.fromJson(character.speechRouteJson)
        val summary = if (!route.isConfigured) {
            "未绑定发言人"
        } else {
            listOfNotNull(
                route.speakerName.takeIf { it.isNotBlank() },
                route.emotionName.takeIf { it.isNotBlank() },
                when (route.engineType) {
                    SpeechRoute.ENGINE_HTTP -> "HTTP"
                    SpeechRoute.ENGINE_SYSTEM -> "系统"
                    else -> null
                }
            ).joinToString(" · ").ifBlank { "已绑定发言人" }
        }
        DubbingRoleUi(
            character = character,
            color = roleColorFor(character, index),
            voiceSummary = voiceSummaryOf(character),
            routeSummary = summary
        )
    }
}
