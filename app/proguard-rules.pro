# release 没有开启混淆（minifyEnabled false），暂时不需要额外的 keep 规则。
# 以后若打开混淆，记得保留 org.json 与反射用到的类（debug.SelfCheck 的 TimetableProbe 会反射读字段）。
