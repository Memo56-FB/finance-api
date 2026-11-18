package dev.memofb.finance.shared;

import java.util.List;

public record ItemsResponse<T>(List<T> items) { }
