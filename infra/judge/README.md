# Judge0 activation boundary

The target is a pinned, self-hosted Judge0 deployment on a dedicated EC2 instance. It is not part of the default local Compose stack because user-code execution changes the host security boundary and the exact AWS account, image digest, and supported-language versions are still unbound.

Do not call this integration ready until all of the following are evidenced:

1. Pin an upstream Judge0 release and every image by digest after code and vulnerability review.
2. Restrict the instance security group to the judge service and operator access; deny outbound network access from submissions.
3. Configure authentication, request size, CPU, wall-clock, memory, process, and output limits.
4. Record language IDs and compiler/runtime versions for C, C++, Java, Python 3, JavaScript, and TypeScript.
5. Pass golden accepted, wrong-answer, compilation-error, runtime-error, timeout, memory-limit, and platform-failure cases.
6. Verify that source, tokens, compiler output, and test inputs are absent from logs and public events.

The judge service must normalize Judge0 responses behind `contracts/http/judge.openapi.json`; no other service may call Judge0 directly.
