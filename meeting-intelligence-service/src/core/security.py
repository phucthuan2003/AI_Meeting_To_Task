from hmac import compare_digest

from fastapi import HTTPException, Request, Security
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer

bearer = HTTPBearer(auto_error=False)


def authorized(expected: str, supplied: str | None) -> bool:
    return bool(expected and supplied and compare_digest(expected.encode(), supplied.encode()))


async def require_key(
    request: Request, credentials: HTTPAuthorizationCredentials | None = Security(bearer)
) -> None:
    if not authorized(request.app.state.settings.api_key, credentials.credentials if credentials else None):
        raise HTTPException(401, detail="UNAUTHORIZED", headers={"WWW-Authenticate": "Bearer"})
