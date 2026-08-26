package com.armsone.button.model

enum class FamilyRole(
    val rawValue: String,
    val title: String,
    val subtitle: String,
    val symbolName: String,
) {
    Parent(
        rawValue = "parent",
        title = "부모",
        subtitle = "음성과 호출을 주고받아요",
        symbolName = "figure.and.child.holdinghands",
    ),
    Child(
        rawValue = "child",
        title = "자녀",
        subtitle = "음성과 호출을 주고받아요",
        symbolName = "face.smiling",
    ),
    General(
        rawValue = "general",
        title = "일반",
        subtitle = "음성과 호출을 주고받아요",
        symbolName = "person",
    );

    companion object {
        fun fromRawValue(value: String): FamilyRole? = entries.firstOrNull { it.rawValue == value }
    }
}
