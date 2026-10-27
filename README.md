<p align="center"><img src="docs/icon.png" width="160" alt="Editor Video"></p>

# Editor Video

Editor de vídeo para Android, **inspirado no Adobe Premiere**. Sem login, sem marca d'água e sem assinatura.

> **Aviso:** projeto independente, sem nenhuma ligação com a Adobe. "Adobe" e "Premiere" são marcas registradas da Adobe Inc. O código foi escrito do zero (Kotlin, Jetpack Compose e Media3) e não usa código, arquivos ou recursos do aplicativo original.

## Recursos

- **Clipes:** juntar vários vídeos, cortar início e fim, dividir no ponto atual, reordenar, remover e silenciar clipes
- **Texto:** títulos e legendas com tempo de início e fim, posição, tamanho e cor
- **Cor:** brilho, contraste, saturação, temperatura e filtros prontos (Vívido, Quente, Frio, P&B, Cinema)
- **Áudio:** música de fundo com controle de volume
- **Projeto:** salvo automaticamente e reaberto quando você volta ao app
- **Exportar:** MP4 (H.264 + AAC) em 720p ou 1080p, proporção Auto, 16:9, 9:16 ou 1:1 (com bordas ou preenchendo o quadro), salvo em `Filmes/EditorVideo`, com progresso em notificação e exportação em segundo plano

Tudo funciona offline e os vídeos nunca saem do seu aparelho.

## Limitações da primeira versão

- Sem transições entre clipes, camadas de vídeo, keyframes ou legendas automáticas
- Pré-visualização aproximada: o resultado final vem da exportação

## Como gerar o APK

1. Instale o [Android Studio](https://developer.android.com/studio) e o Git.
2. Abra a pasta em **File > Open** e espere o Gradle sincronizar (use um JDK entre 17 e 22 em **Settings > Build Tools > Gradle > Gradle JDK**).
3. Para testar, clique em **Run** com o celular conectado ou um emulador.
4. Para o APK: **Build > Generate App Bundles or APKs > Generate APKs**.

O APK de teste fica em `app/build/outputs/apk/debug/app-debug.apk`.

**Requisitos:** Android 10 ou mais novo (minSdk 29).

## Tecnologias

Kotlin, Jetpack Compose, Material 3 e [AndroidX Media3](https://developer.android.com/media/media3) (ExoPlayer, Effect e Transformer).
