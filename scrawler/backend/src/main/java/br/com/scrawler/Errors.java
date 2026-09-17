package br.com.scrawler;

import java.util.*;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;

@RestControllerAdvice
public class Errors {
  @ExceptionHandler(Store.Missing.class)
  ResponseEntity<?> missing() {
    return ResponseEntity.status(404).body(Map.of("erro", "Registro nao encontrado"));
  }

  @ExceptionHandler(IllegalArgumentException.class)
  ResponseEntity<?> bad(IllegalArgumentException e) {
    return ResponseEntity.badRequest()
        .body(Map.of("erro", e.getMessage() == null ? "Entrada invalida" : e.getMessage()));
  }

  @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class})
  ResponseEntity<?> invalid(Exception e) {
    return ResponseEntity.badRequest()
        .body(Map.of("erro", "Campos ausentes, desconhecidos ou invalidos. Consulte docs/API.md."));
  }

  @ExceptionHandler(DataIntegrityViolationException.class)
  ResponseEntity<?> conflict(DataIntegrityViolationException e) {
    return ResponseEntity.status(409)
        .body(
            Map.of(
                "erro",
                "Operacao viola integridade: verifique duplicidade, referencias, estado e"
                    + " compatibilidade das evidencias."));
  }
}
