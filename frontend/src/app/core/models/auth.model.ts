// FR-1.1, FR-1.3: Auth request/response shapes matching backend DTOs.

export interface LoginRequest {
  email:    string;
  password: string;
}

export interface LoginResponse {
  accessToken: string;
  tokenType:   string;
  expiresAt:   string; // ISO instant string
}
