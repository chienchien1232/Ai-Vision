package com.rayban.ai.domain.model

enum class AssistantTask {
    DescribeScene,
    IdentifyObject,
    ReadText,
    SummarizeScene,
    OpenQuestion,
}

enum class FramePolicy {
    Fresh,
    RecentOrFresh,
}