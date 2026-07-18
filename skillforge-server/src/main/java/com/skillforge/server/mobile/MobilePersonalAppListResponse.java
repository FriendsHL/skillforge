package com.skillforge.server.mobile;

import java.util.List;

public record MobilePersonalAppListResponse(
        List<MobilePersonalAppItemResponse> items,
        String nextCursor) { }
