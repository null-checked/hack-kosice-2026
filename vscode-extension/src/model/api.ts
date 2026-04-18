import type { DeveloperEvent } from './events';

/** Request/response DTOs for the four backend HTTP endpoints. */

export interface ContextFile {
  path: string;
  content: string;
}

export interface GenerateTestsRequest {
  language: string;
  code: string;
  function_name?: string | null;
  file_path?: string | null;
  context_files?: ContextFile[];
}

export interface GenerateTestsResponse {
  test_code: string;
  test_file_name: string;
  framework: string;
  reasoning: string;
}

export interface SelectionRange {
  start_line: number;
  end_line: number;
}

export interface SuggestRefactorRequest {
  language: string;
  code: string;
  file_path?: string | null;
  selection_range?: SelectionRange | null;
  smell_hints?: string[];
}

export interface DetectedSmell {
  type: string;
  line: number;
  description: string;
}

export interface SuggestRefactorResponse {
  refactored_code: string;
  diff_unified: string;
  smells_detected: DetectedSmell[];
  reasoning: string;
}

export interface SocraticRequest {
  recent_events: DeveloperEvent[];
  current_code: string;
  stuck_reason: string;
}

export interface SocraticResponse {
  question: string;
  follow_ups: string[];
  confidence: number;
}

export interface HealthResponse {
  status: string;
  llm_provider: string;
  llm_model: string;
  llm_available: boolean;
  demo_mock: boolean;
  version: string;
}
