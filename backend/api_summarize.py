"""AI 视频总结相关 API 路由（独立模块，通过 include_router 挂载）"""

import asyncio
import json
from collections.abc import AsyncIterable

from fastapi import APIRouter, HTTPException
from fastapi.sse import EventSourceResponse, ServerSentEvent
from pydantic import BaseModel

router = APIRouter(prefix="/api", tags=["AI 总结"])


class SummarizeRequest(BaseModel):
    url: str
    language: str = "zh"


class ChatRequest(BaseModel):
    url: str
    question: str
    subtitle_text: str = ""


def _get_summarizer():
    """延迟初始化 VideoSummarizer（仅在首次调用时创建）"""
    from summarizer import VideoSummarizer
    if not hasattr(_get_summarizer, "_instance"):
        try:
            _get_summarizer._instance = VideoSummarizer()
        except ValueError as e:
            raise HTTPException(status_code=500, detail=str(e))
    return _get_summarizer._instance


def _get_extractor():
    """延迟初始化 SubtitleExtractor"""
    from summarizer import SubtitleExtractor
    if not hasattr(_get_extractor, "_instance"):
        _get_extractor._instance = SubtitleExtractor()
    return _get_extractor._instance


@router.post("/summarize", response_class=EventSourceResponse)
async def summarize_video(req: SummarizeRequest) -> AsyncIterable[ServerSentEvent]:
    """
    AI 视频总结（SSE 流式）
    事件类型: subtitle / summary / mindmap / done / error
    """
    try:
        loop = asyncio.get_event_loop()
        extractor = _get_extractor()
        subtitle_data = await loop.run_in_executor(
            None, extractor.extract, req.url
        )

        yield ServerSentEvent(
            raw_data=json.dumps(subtitle_data, ensure_ascii=False),
            event="subtitle",
        )

        if not subtitle_data["has_subtitle"]:
            yield ServerSentEvent(
                raw_data=json.dumps({"message": "该视频没有可用的字幕，无法生成总结"}, ensure_ascii=False),
                event="error",
            )
            return

        full_text = subtitle_data["full_text"]
        summarizer = _get_summarizer()

        for token in summarizer.summarize_stream(full_text, req.language):
            yield ServerSentEvent(raw_data=token, event="summary")

        mindmap_md = await loop.run_in_executor(
            None, summarizer.generate_mindmap, full_text, req.language
        )
        yield ServerSentEvent(
            raw_data=json.dumps({"markdown": mindmap_md}, ensure_ascii=False),
            event="mindmap",
        )

        yield ServerSentEvent(raw_data="[DONE]", event="done")

    except Exception as e:
        yield ServerSentEvent(
            raw_data=json.dumps({"message": f"总结失败: {str(e)}"}, ensure_ascii=False),
            event="error",
        )


@router.post("/chat", response_class=EventSourceResponse)
async def chat_with_video(req: ChatRequest) -> AsyncIterable[ServerSentEvent]:
    """AI 视频问答（SSE 流式）"""
    try:
        if not req.subtitle_text.strip():
            loop = asyncio.get_event_loop()
            extractor = _get_extractor()
            subtitle_data = await loop.run_in_executor(
                None, extractor.extract, req.url
            )
            if not subtitle_data["has_subtitle"]:
                yield ServerSentEvent(
                    raw_data=json.dumps({"message": "该视频没有可用的字幕，无法回答问题"}, ensure_ascii=False),
                    event="error",
                )
                return
            subtitle_text = subtitle_data["full_text"]
        else:
            subtitle_text = req.subtitle_text

        summarizer = _get_summarizer()
        for token in summarizer.chat_stream(subtitle_text, req.question):
            yield ServerSentEvent(raw_data=token, event="answer")

        yield ServerSentEvent(raw_data="[DONE]", event="done")

    except Exception as e:
        yield ServerSentEvent(
            raw_data=json.dumps({"message": f"回答失败: {str(e)}"}, ensure_ascii=False),
            event="error",
        )
