package ke.chama.modules.dataimport.presentation.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import ke.chama.modules.dataimport.application.port.in.OpeningBalanceImports;
import ke.chama.modules.dataimport.application.port.in.OpeningBalanceImports.ImportView;
import ke.chama.modules.dataimport.application.port.in.OpeningBalanceImports.PreviewView;
import ke.chama.modules.dataimport.domain.exception.ImportExceptions.InvalidImportFileException;
import ke.chama.shared.security.ChamaPermission;
import ke.chama.shared.security.CurrentUser;
import ke.chama.shared.security.RequiresChamaPermission;
import ke.chama.shared.tenancy.TenantContext;
import ke.chama.shared.web.ApiResponse;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Bringing a chama's records over from Excel. Chairperson by default (CHAMA_SETTINGS_MANAGE).
 * <pre>
 * GET  /api/v1/imports/opening-balances/template   the .xlsx template
 * POST /api/v1/imports/opening-balances/preview    multipart: file, asOf - checks every row, saves nothing
 * POST /api/v1/imports/opening-balances            multipart: file, asOf - imports, once, all or nothing
 * GET  /api/v1/imports/opening-balances            the import, if done
 * </pre>
 */
@Tag(name = "Imports", description = "Bring opening balances over from the chama's Excel records")
@RestController
@RequestMapping("/api/v1/imports/opening-balances")
class OpeningBalanceImportController {

    static final MediaType XLSX = MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");

    private final OpeningBalanceImports imports;

    OpeningBalanceImportController(OpeningBalanceImports imports) {
        this.imports = imports;
    }

    @Operation(summary = "Download the template",
            description = "An .xlsx workbook with instructions and the Members, Loans, Accounts and Investments sheets. "
                    + "Members has a savings column per savings product (add products before downloading).")
    @GetMapping("/template")
    @RequiresChamaPermission(ChamaPermission.CHAMA_SETTINGS_MANAGE)
    ResponseEntity<byte[]> template() {
        return ResponseEntity.ok()
                .contentType(XLSX)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename("opening-balances-template.xlsx").build().toString())
                .body(imports.template(TenantContext.requireChama()));
    }

    @Operation(summary = "Preview an import",
            description = "Checks every row against the chama and lists each problem with its sheet, row and column, "
                    + "with the totals that would be brought forward. Nothing is saved.")
    @PostMapping(path = "/preview", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @RequiresChamaPermission(ChamaPermission.CHAMA_SETTINGS_MANAGE)
    ApiResponse<PreviewView> preview(@RequestParam("file") MultipartFile file,
                                     @RequestParam("asOf") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf) {
        PreviewView preview = imports.preview(chama(), bytes(file), asOf);
        return ApiResponse.ok(preview, preview.importable() ? "Ready to import"
                : preview.errors() + " problem" + (preview.errors() == 1 ? "" : "s") + " to fix");
    }

    @Operation(summary = "Import opening balances",
            description = "Creates the members (each emailed a link to set a password), posts one opening-balance entry "
                    + "dated the cut-over day, and records the loans, penalties and investments. Done once; all or nothing.")
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @RequiresChamaPermission(ChamaPermission.CHAMA_SETTINGS_MANAGE)
    ApiResponse<ImportView> importBalances(@RequestParam("file") MultipartFile file,
                                           @RequestParam("asOf") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf) {
        UUID chamaId = chama();
        return ApiResponse.ok(imports.importBalances(chamaId, CurrentUser.id(), file.getOriginalFilename(), bytes(file),
                asOf), "Opening balances imported");
    }

    @Operation(summary = "The import, if done")
    @GetMapping
    @RequiresChamaPermission(ChamaPermission.CHAMA_SETTINGS_MANAGE)
    ApiResponse<ImportView> imported() {
        return imports.imported(chama())
                .map(view -> ApiResponse.ok(view, "Opening balances imported as at " + view.asOf()))
                .orElseGet(() -> ApiResponse.ok(null, "Opening balances have not been imported yet"));
    }

    private static byte[] bytes(MultipartFile file) {
        try {
            return file.getBytes();
        } catch (IOException e) {
            throw new InvalidImportFileException("The file could not be uploaded");
        }
    }

    private static UUID chama() {
        return TenantContext.requireChama();
    }
}
