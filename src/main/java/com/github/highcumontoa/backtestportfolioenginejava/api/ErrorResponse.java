package com.github.highcumontoa.backtestportfolioenginejava.api;

/** 统一对外错误体：分类 + 稳定错误码 + 可读消息，绝不透出内部堆栈。 */
public record ErrorResponse(String category, String code, String message) {
}
