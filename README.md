# Merged into `atom-basic-steps`

通用、与任何外部产品无关的文件动作集中到一个原子（对应 Jenkins 的 Pipeline Basic Steps）。

```groovy
// 旧
runAtom(atom: 'text-file', version: '1.latest', inputs: [...])
// 新
runAtom(atom: 'atom-basic-steps', version: '1.2.0', inputs: [action: 'write-file', ...])
```

参数与错误码沿用原实现（path/content/option 不变）。
本仓库只读保留，便于历史构建追溯。
