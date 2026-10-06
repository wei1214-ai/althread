package com.example.myapplicationkoG.ui.editor

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.material3.MaterialTheme
import coil.compose.AsyncImage
import com.example.myapplicationkoG.VtonRepository
import com.example.myapplicationkoG.textColorForTheme
import com.example.myapplicationkoG.ui.theme.Cyan
import com.example.myapplicationkoG.ui.theme.MidnightBlue
import kotlinx.coroutines.launch
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VtonDialog(
    garmentFile: File?,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repo = remember { VtonRepository() }

    var personUri by remember { mutableStateOf<Uri?>(null) }
    var running by remember { mutableStateOf(false) }
    var resultFile by remember { mutableStateOf<File?>(null) }
    var saving by remember { mutableStateOf(false) }

    val pickPerson = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let {
            personUri = it
            resultFile = null
        }
    }

    Dialog(onDismissRequest = { if (!running) onDismiss() }) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(MaterialTheme.colorScheme.surface)
                .padding(20.dp)
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Virtual Try-On", fontWeight = FontWeight.ExtraBold, fontSize = 18.sp, color = textColorForTheme(MidnightBlue))
                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("You", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = textColorForTheme(MidnightBlue))
                        Spacer(modifier = Modifier.height(4.dp))
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(140.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                .clickable { pickPerson.launch("image/*") },
                            contentAlignment = Alignment.Center
                        ) {
                            if (personUri != null) {
                                AsyncImage(
                                    model = personUri,
                                    contentDescription = "Person",
                                    modifier = Modifier.fillMaxWidth().height(140.dp),
                                    contentScale = ContentScale.Crop
                                )
                            } else {
                                Text("Pick photo", color = textColorForTheme(MidnightBlue), fontSize = 12.sp)
                            }
                        }
                    }
                    Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Garment", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = textColorForTheme(MidnightBlue))
                        Spacer(modifier = Modifier.height(4.dp))
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(140.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant),
                            contentAlignment = Alignment.Center
                        ) {
                            if (garmentFile?.exists() == true) {
                                AsyncImage(
                                    model = garmentFile,
                                    contentDescription = "Garment",
                                    modifier = Modifier.fillMaxWidth().height(140.dp),
                                    contentScale = ContentScale.Crop
                                )
                            } else {
                                Text("No garment", color = textColorForTheme(Color.Gray), fontSize = 12.sp)
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                if (running) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), color = MidnightBlue, strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.padding(4.dp))
                        Text("Trying on... (free queue, ~1-2 min)", fontSize = 13.sp, color = textColorForTheme(Color.Gray))
                    }
                } else if (resultFile != null) {
                    AsyncImage(
                        model = resultFile,
                        contentDescription = "Try-on result",
                        modifier = Modifier.fillMaxWidth().height(300.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
                        contentScale = ContentScale.Fit
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        TextButton(
                            onClick = {
                                saving = true
                                scope.launch {
                                    try {
                                        repo.saveToGallery(context, resultFile!!, "tryon_${System.currentTimeMillis()}")
                                        Toast.makeText(context, "Saved to gallery", Toast.LENGTH_SHORT).show()
                                    } catch (e: Exception) {
                                        Toast.makeText(context, "Save failed: ${e.message}", Toast.LENGTH_SHORT).show()
                                    } finally {
                                        saving = false
                                    }
                                }
                            },
                            enabled = !saving
                        ) {
                            Text(if (saving) "Saving..." else "Save image", color = textColorForTheme(MidnightBlue), fontWeight = FontWeight.Bold)
                        }
                        TextButton(onClick = onDismiss) {
                            Text("Close", color = textColorForTheme(Color.Gray))
                        }
                    }
                } else {
                    Button(
                        onClick = {
                            if (personUri == null) {
                                Toast.makeText(context, "Pick your photo first", Toast.LENGTH_SHORT).show()
                                return@Button
                            }
                            if (garmentFile?.exists() != true) {
                                Toast.makeText(context, "No garment image", Toast.LENGTH_SHORT).show()
                                return@Button
                            }
                            running = true
                            scope.launch {
                                try {
                                    val personFile = File(context.cacheDir, "vton_person_${System.currentTimeMillis()}.jpg")
                                    context.contentResolver.openInputStream(personUri!!)?.use { input ->
                                        personFile.outputStream().use { input.copyTo(it) }
                                    }
                                    resultFile = repo.runTryOn(personFile, garmentFile!!)
                                } catch (e: Exception) {
                                    Toast.makeText(context, "Try-on failed: ${e.message}", Toast.LENGTH_LONG).show()
                                } finally {
                                    running = false
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth().height(50.dp),
                        shape = RoundedCornerShape(25.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Cyan, contentColor = MidnightBlue)
                    ) {
                        Text("Try On", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    TextButton(onClick = onDismiss) {
                        Text("Cancel", color = textColorForTheme(Color.Gray))
                    }
                }
            }
        }
    }
}
