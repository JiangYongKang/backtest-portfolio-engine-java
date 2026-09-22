package com.github.highcumontoa.backtestportfolioenginejava.portfolio;

import com.github.highcumontoa.backtestportfolioenginejava.error.InvalidParameterException;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** 标的 -> 报价币种 映射（本地回测的静态参考数据）。 */
@Component
public class SymbolCurrencyRegistry {

    private final Map<String, String> mapping = new ConcurrentHashMap<>();

    public void register(String symbol, String ccy) {
        if (symbol == null || symbol.isBlank() || ccy == null || ccy.isBlank()) {
            throw new InvalidParameterException("symbol and currency are required");
        }
        mapping.put(symbol, ccy);
    }

    public Map<String, String> mapping() {
        return mapping;
    }
}
