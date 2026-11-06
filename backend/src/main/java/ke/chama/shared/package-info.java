/**
 * Shared kernel: Money, domain event and aggregate base types, the exception hierarchy,
 * web error handling and cross-cutting config.
 * <p>
 * OPEN so every module can use it. Keep it free of business rules: when modules are extracted,
 * this package becomes a small library that every service depends on.
 */
@ApplicationModule(displayName = "Shared Kernel", type = ApplicationModule.Type.OPEN)
package ke.chama.shared;

import org.springframework.modulith.ApplicationModule;
