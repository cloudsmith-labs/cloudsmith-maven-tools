---
name: Bug report
about: Report a bug or unexpected behavior
title: "[BUG] "
labels: bug
---

## Description

<!-- Describe the bug. -->

## Steps to reproduce

1.
2.
3.

## Expected behavior

<!-- Describe what you expected to happen. -->

## Actual behavior

<!-- Describe what happened. -->

## Environment

- **OS**: <!-- e.g. macOS 15, Ubuntu 24.04, Windows 11 -->
- **Maven or Gradle, and Java versions**: <!-- mvn -v, or gradle -v -->
- **Extension or plugin version**: <!-- from .mvn/extensions.xml, or the plugins block -->
- **Cloudsmith CLI version**: <!-- cloudsmith --version -->

## Diagnostics

<!--
Paste the output of these commands:

  1. Which hosts does the CLI report?
       cloudsmith domains list

  2. Does the CLI have a credential? (Do not paste the token.)
       cloudsmith credential-helper generic > /dev/null && echo ok

  3. Which repositories get the CLI credential? (The log never shows the token.)
       mvn -X <your goal> 2>&1 | grep -i cloudsmith
       gradle --info <your task> 2>&1 | grep -i cloudsmith
-->

```
paste output here
```

## Additional context

<!-- Add any other context. -->
