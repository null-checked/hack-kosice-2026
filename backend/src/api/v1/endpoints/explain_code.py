from fastapi import APIRouter, Request
from pydantic import BaseModel
from backend.src.core.llm_client import get_llm_client
from backend.src.core.prompts import build_explain_prompt

router = APIRouter()

class ExplainCodeRequest(BaseModel):
    file_path: str
    code: str
    context: str = ""

@router.post("/explain-code")
async def explain_code(req: ExplainCodeRequest):
    prompt = build_explain_prompt(req.code, req.file_path, req.context)
    llm = get_llm_client()
    result = await llm.complete(prompt)
    # Assume result is a dict with explanation, complexity, suggestions
    return result
